#Requires -Version 5.1
# Dependency-free regression runner. Docker is mocked; only disposable fixtures are written.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Test-ComposePreflight.ps1')
$nativeRunner = ${function:Invoke-PreflightDocker}
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('zentro-preflight-' + [guid]::NewGuid().ToString('N'))
$passed = 0

function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-PreflightDockerCommand {
    if ($script:scenario -ne 'no-cli') { return 'mock-docker' }
}
function Invoke-PreflightDocker {
    param([string]$DockerPath, [string[]]$Arguments)
    $command = $Arguments -join ' '
    $script:calls.Add($command)
    if ($script:scenario -eq 'broken-cli' -and $command -eq '--version') { return $false }
    if ($script:scenario -eq 'no-compose' -and $command -eq 'compose version') { return $false }
    if ($script:scenario -eq 'no-daemon' -and $command -eq 'info') { return $false }
    if ($script:scenario -eq 'bad-config' -and $command -match 'config -q$') { return $false }
    return $true
}

try {
    $null = New-Item -ItemType Directory -Path $fixture
    $files = @(
        'docker-compose.yml', 'backend/docker-compose.yml', 'infra/keycloak/docker-compose.keycloak.yml',
        'backend/identity-partner-service/.env', 'backend/api-gateway/.env',
        'backend/parcel-pricing-service/.env', 'backend/dispatch-courier-service/.env',
        'backend/tracking-reporting-service/.env', 'backend/notification-integration-service/.env',
        'infra/keycloak/.env'
    )
    foreach ($relative in $files) {
        $path = Join-Path $fixture $relative
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force
        [IO.File]::WriteAllText($path, 'synthetic-private-value')
    }
    $before = @{}
    foreach ($relative in $files) { $before[$relative] = (Get-FileHash -LiteralPath (Join-Path $fixture $relative)).Hash }
    $locationBefore = (Get-Location).Path

    foreach ($script:scenario in @('success', 'no-cli', 'broken-cli', 'no-compose', 'no-daemon', 'bad-config', 'missing-env', 'missing-compose')) {
        $script:calls = New-Object 'System.Collections.Generic.List[string]'
        $hidden = $null
        if ($scenario -eq 'missing-env') { $hidden = Join-Path $fixture 'backend/tracking-reporting-service/.env' }
        if ($scenario -eq 'missing-compose') { $hidden = Join-Path $fixture 'docker-compose.yml' }
        if ($hidden) { Move-Item -LiteralPath $hidden -Destination ($hidden + '.held') }
        try {
            $report = Invoke-ComposePreflight -Root $fixture
            $failures = @($report.Results | Where-Object Status -eq 'FAIL')
            Assert-True ($report.ExitCode -eq [int]($scenario -ne 'success')) "$scenario exit code"
            Assert-True (($scenario -eq 'success') -or $failures.Count -gt 0) "$scenario failure result"
            $configCalls = @($calls | Where-Object { $_ -match 'config -q$' })
            $expected = 3
            if ($scenario -in @('no-cli', 'broken-cli', 'no-compose')) { $expected = 0 }
            if ($scenario -in @('missing-env', 'missing-compose')) { $expected = 2 }
            Assert-True ($configCalls.Count -eq $expected) "$scenario config gating"
            foreach ($call in $calls) {
                Assert-True ($call -in @('--version', 'compose version', 'info',
                    'compose -f docker-compose.yml config -q',
                    'compose -f backend/docker-compose.yml config -q',
                    'compose --env-file infra/keycloak/.env -f infra/keycloak/docker-compose.keycloak.yml config -q')) 'Unexpected Docker command'
            }
            if ($scenario -eq 'missing-env') {
                Assert-True (@($report.Results | Where-Object { $_.Check -eq 'backend/tracking-reporting-service/.env' -and $_.Status -eq 'FAIL' }).Count -eq 1) 'Missing filename absent'
                Assert-True (@($report.Results | Where-Object { $_.Check -eq 'docker-compose.yml' -and $_.Status -eq 'SKIP' }).Count -eq 1) 'Root config was not skipped'
            }
            Assert-True (($report.Results | Out-String) -notmatch 'synthetic-private-value') 'Fixture value leaked'
            Assert-True ((Get-Location).Path -eq $locationBefore) 'Location changed'
            $passed++
            Write-Output "PASS $scenario"
        }
        finally { if ($hidden) { Move-Item -LiteralPath ($hidden + '.held') -Destination $hidden } }
    }

    # Exercise the actual native-output suppression wrapper with a harmless child PowerShell.
    $shell = (Get-Process -Id $PID).Path
    foreach ($exitCode in @(0, 1)) {
        $command = "[Console]::Out.WriteLine('synthetic-private-value'); [Console]::Error.WriteLine('synthetic-private-value'); exit $exitCode"
        $output = @(& $nativeRunner $shell @('-NoProfile', '-Command', $command) *>&1)
        Assert-True ($output.Count -eq 1 -and $output[0] -is [bool]) 'Native output leaked'
        Assert-True ($output[0] -eq ($exitCode -eq 0)) 'Native exit code lost'
    }
    $passed++
    Write-Output 'PASS native stdout/stderr suppression and exit codes'
    $entryOutput = @(& $shell -NoProfile -File (Join-Path $PSScriptRoot 'Test-ComposePreflight.ps1') -RepositoryRoot (Join-Path $fixture 'absent') 2>&1)
    Assert-True ($LASTEXITCODE -eq 1) 'Script process did not fail for missing repository'
    Assert-True ($entryOutput.Count -eq 1 -and $entryOutput[0] -eq 'FAIL Repository: Directory unavailable.') 'Unexpected script output'
    $passed++
    Write-Output 'PASS script entry point and nonzero process exit'
    foreach ($relative in $files) {
        Assert-True ((Get-FileHash -LiteralPath (Join-Path $fixture $relative)).Hash -eq $before[$relative]) 'Fixture was modified'
    }
    Assert-True (@(Get-ChildItem -LiteralPath $fixture -Recurse -File -Force).Count -eq $files.Count) 'Unexpected fixture files'
    $passed++
    Write-Output 'PASS fixture files unchanged'
    Write-Output "$passed tests passed; 0 failed."
}
finally {
    # Delete only this runner's GUID-named directory directly beneath the OS temp directory.
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ((Split-Path -Parent $resolved) -eq $tempRoot -and (Split-Path -Leaf $resolved) -match '^zentro-preflight-[a-f0-9]{32}$') {
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
}
