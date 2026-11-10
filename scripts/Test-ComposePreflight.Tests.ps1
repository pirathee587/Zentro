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
function Invoke-PreflightDockerCapture {
    param([string]$DockerPath, [string[]]$Arguments)
    $command = $Arguments -join ' '
    $script:captureCalls.Add($command)
    if ($command -eq 'compose config --help') {
        return [pscustomobject]@{ Success = $true; Text = '--no-env-resolution' }
    }
    Assert-True ($command -match '^compose --env-file NUL -f (docker-compose.yml|backend/docker-compose.yml|infra/keycloak/docker-compose.keycloak.yml) config --format json --no-interpolate --no-env-resolution --no-normalize --no-path-resolution$') 'Unexpected capture command'
    if ($script:scenario -eq 'source-fail') {
        return [pscustomobject]@{ Success = $true; Text = '{"services":{"fixture":{"healthcheck":{"disable":true}}}}' }
    }
    return [pscustomobject]@{ Success = $true; Text = '{"services":{"fixture":{"image":"fixture"}}}' }
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

    foreach ($script:scenario in @('success', 'no-cli', 'broken-cli', 'no-compose', 'no-daemon', 'bad-config', 'missing-env', 'missing-compose', 'source-fail')) {
        $script:calls = New-Object 'System.Collections.Generic.List[string]'
        $script:captureCalls = New-Object 'System.Collections.Generic.List[string]'
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
            $sourceCount = 3
            if ($scenario -in @('no-cli', 'broken-cli', 'no-compose')) { $sourceCount = 0 }
            if ($scenario -eq 'missing-compose') { $sourceCount = 2 }
            Assert-True (@($captureCalls | Where-Object { $_ -match '--format json' }).Count -eq $sourceCount) "$scenario source inspection gating"
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

    # Exercise source inspection independently of real Docker and environment contents.
    function Invoke-PreflightDockerCapture {
        param([string]$DockerPath, [string[]]$Arguments)
        $command = $Arguments -join ' '
        if ($command -eq 'compose config --help') {
            return [pscustomobject]@{ Success = $true; Text = $script:helpText }
        }
        Assert-True ($command -eq 'compose --env-file NUL -f docker-compose.yml config --format json --no-interpolate --no-env-resolution --no-normalize --no-path-resolution') 'Unsafe source command'
        if ($script:sourceCase -eq 'exception') { throw 'synthetic-private-value' }
        return [pscustomobject]@{ Success = ($script:sourceCase -ne 'rejected'); Text = $script:sourceText }
    }
    $cases = @(
        @{ Name = 'health'; Json = '{"services":{"api-gateway":{"healthcheck":{"test":["CMD","probe"]}}}}'; Status = 'PASS'; Check = 'api-gateway healthcheck' },
        @{ Name = 'missing-health'; Json = '{"services":{"zentro-web":{"image":"fixture"}}}'; Status = 'WARN'; Check = 'zentro-web healthcheck' },
        @{ Name = 'disabled-health'; Json = '{"services":{"keycloak":{"healthcheck":{"disable":true}}}}'; Status = 'FAIL'; Check = 'keycloak healthcheck' },
        @{ Name = 'none-health'; Json = '{"services":{"s1":{"healthcheck":{"test":["NONE"]}}}}'; Status = 'FAIL'; Check = 's1 healthcheck' },
        @{ Name = 'started'; Json = '{"services":{"a":{"depends_on":{"b":{"condition":"service_started"}}},"b":{}}}'; Status = 'WARN'; Check = 'a dependencies'; Detail = 'only for container startup' },
        @{ Name = 'short-dependency'; Json = '{"services":{"a":{"depends_on":["b"]},"b":{}}}'; Status = 'WARN'; Check = 'a dependencies'; Detail = 'Short dependency' },
        @{ Name = 'healthy-gate'; Json = '{"services":{"a":{"depends_on":{"b":{"condition":"service_healthy"}}},"b":{"healthcheck":{"test":["CMD","probe"]}}}}'; Status = 'WARN'; Check = 'a dependencies'; Detail = 'Waits for service health' },
        @{ Name = 'disabled-gate'; Json = '{"services":{"a":{"depends_on":{"b":{"condition":"service_healthy"}}},"b":{"healthcheck":{"disable":true}}}}'; Status = 'FAIL'; Check = 'a dependencies' },
        @{ Name = 'completed'; Json = '{"services":{"a":{"depends_on":{"b":{"condition":"service_completed_successfully"}}},"b":{}}}'; Status = 'WARN'; Check = 'a dependencies'; Detail = 'successful completion' },
        @{ Name = 'liveness'; Json = '{"services":{"s1":{"healthcheck":{"test":["CMD","/health/liveness"]}}}}'; Status = 'WARN'; Check = 's1 readiness' },
        @{ Name = 'readiness'; Json = '{"services":{"s1":{"healthcheck":{"test":["CMD","/health/readiness"]}}}}'; Status = 'WARN'; Check = 's1 liveness' },
        @{ Name = 'naming'; Json = '{"services":{"s1":{"container_name":"synthetic-private-value","ports":["8080:80"]}},"networks":{"n":{"name":"synthetic-private-value"}},"volumes":{"v":{"external":true}}}'; Status = 'WARN'; Check = 's1 isolation' },
        @{ Name = 'long-port'; Json = '{"services":{"s1":{"ports":[{"target":80,"published":"8080"}]}}}'; Status = 'WARN'; Check = 's1 ports' },
        @{ Name = 'malformed'; Json = 'synthetic-private-value {'; Status = 'FAIL'; Check = 'source inspection' },
        @{ Name = 'no-services'; Json = '{}'; Status = 'FAIL'; Check = 'source inspection' },
        @{ Name = 'empty-services'; Json = '{"services":{}}'; Status = 'FAIL'; Check = 'source inspection' },
        @{ Name = 'rejected'; Json = 'synthetic-private-value'; Status = 'FAIL'; Check = 'source inspection' },
        @{ Name = 'exception'; Json = ''; Status = 'FAIL'; Check = 'source inspection' },
        @{ Name = 'unsupported'; Json = ''; Status = 'SKIP'; Check = 'source inspection' }
    )
    foreach ($case in $cases) {
        $script:sourceCase = $case.Name
        $script:sourceText = $case.Json
        $script:helpText = '--no-env-resolution'
        if ($case.Name -eq 'unsupported') { $script:helpText = '' }
        $findings = @(Get-ComposeSourceFindings -DockerPath 'mock-docker' -File 'docker-compose.yml')
        $matching = @($findings | Where-Object { $_.Check -eq $case.Check -and $_.Status -eq $case.Status })
        Assert-True ($matching.Count -gt 0) ($case.Name + ' classification')
        if ($case.Detail) { Assert-True (($matching.Detail -join ' ') -match $case.Detail) ($case.Name + ' semantics') }
        Assert-True (($findings | Out-String) -notmatch 'synthetic-private-value') 'Source secrets leaked'
        if ($case.Name -eq 'naming') {
            foreach ($check in @('s1 ports', 'networks isolation', 'volumes isolation')) {
                Assert-True (@($findings | Where-Object Check -eq $check).Count -eq 1) 'Isolation finding missing'
            }
        }
        $passed++
        Write-Output ('PASS source ' + $case.Name)
    }
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
