#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

function Get-PreflightDockerCommand {
    $command = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
}

function Invoke-PreflightDocker {
    param([string]$DockerPath, [string[]]$Arguments)
    # Discard every native stream, including errors that may contain interpolated secrets.
    $ErrorActionPreference = 'Continue'
    try {
        & $DockerPath @Arguments *> $null
        return ($LASTEXITCODE -eq 0)
    }
    catch { return $false }
}

function Invoke-ComposePreflight {
    param([string]$Root)
    $results = New-Object 'System.Collections.Generic.List[object]'
    function Add-Result([string]$Status, [string]$Check, [string]$Detail) {
        $results.Add([pscustomobject]@{ Status = $Status; Check = $Check; Detail = $Detail })
    }

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        Add-Result FAIL 'Repository' 'Directory unavailable.'
        return [pscustomobject]@{ Results = $results; ExitCode = 1 }
    }

    $docker = Get-PreflightDockerCommand
    $cliAvailable = $false
    $composeAvailable = $false
    if ($docker) { $cliAvailable = Invoke-PreflightDocker $docker @('--version') }
    if ($cliAvailable) {
        Add-Result PASS 'Docker CLI' 'Available.'
        $composeAvailable = Invoke-PreflightDocker $docker @('compose', 'version')
        if ($composeAvailable) { Add-Result PASS 'Docker Compose' 'Available.' }
        else { Add-Result FAIL 'Docker Compose' 'Unavailable; native output suppressed.' }
        if (Invoke-PreflightDocker $docker @('info')) {
            Add-Result PASS 'Docker daemon' 'Reachable in the current Docker context.'
        }
        else { Add-Result FAIL 'Docker daemon' 'Unreachable or access denied; native output suppressed.' }
    }
    else {
        Add-Result FAIL 'Docker CLI' 'Unavailable or could not execute.'
        Add-Result SKIP 'Docker Compose' 'Requires Docker CLI.'
        Add-Result SKIP 'Docker daemon' 'Requires Docker CLI.'
    }

    # Inventory matches the three committed Compose definitions, not unused templates.
    $backendEnvs = @(
        'backend/identity-partner-service/.env', 'backend/api-gateway/.env',
        'backend/parcel-pricing-service/.env', 'backend/dispatch-courier-service/.env',
        'backend/tracking-reporting-service/.env', 'backend/notification-integration-service/.env'
    )
    $stacks = @(
        @{ File = 'docker-compose.yml'; Envs = $backendEnvs },
        @{ File = 'backend/docker-compose.yml'; Envs = $backendEnvs[0..1] },
        @{ File = 'infra/keycloak/docker-compose.keycloak.yml'; Envs = @('infra/keycloak/.env') }
    )
    $present = @{}
    foreach ($relative in @($backendEnvs) + @('infra/keycloak/.env')) {
        $present[$relative] = Test-Path -LiteralPath (Join-Path $Root $relative) -PathType Leaf
        if ($present[$relative]) { Add-Result PASS $relative 'Required environment file exists; values not inspected.' }
        else { Add-Result FAIL $relative 'Required environment file missing.' }
    }

    # A failed daemon check does not prevent local Compose configuration validation.
    Push-Location -LiteralPath $Root
    try {
        foreach ($stack in $stacks) {
            $file = $stack.File
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
                Add-Result FAIL $file 'Compose definition missing.'
                continue
            }
            if (@($stack.Envs | Where-Object { -not $present[$_] }).Count -gt 0) {
                Add-Result SKIP $file 'Configuration validation requires missing environment files listed above.'
                continue
            }
            if (-not $composeAvailable) {
                Add-Result SKIP $file 'Configuration validation requires Docker Compose.'
                continue
            }
            $arguments = @('compose')
            if ($file -eq 'infra/keycloak/docker-compose.keycloak.yml') {
                $arguments += @('--env-file', 'infra/keycloak/.env')
            }
            $arguments += @('-f', $file, 'config', '-q')
            if (Invoke-PreflightDocker $docker $arguments) {
                Add-Result PASS $file 'Quiet configuration validation passed.'
            }
            else { Add-Result FAIL $file 'Configuration validation failed; native output suppressed to protect secrets.' }
        }
    }
    finally { Pop-Location }

    $exitCode = 0
    if (@($results | Where-Object { $_.Status -eq 'FAIL' }).Count -gt 0) { $exitCode = 1 }
    return [pscustomobject]@{ Results = $results; ExitCode = $exitCode }
}

# Dot-sourcing exposes the checks for isolated tests without running Docker or exiting.
if ($MyInvocation.InvocationName -ne '.') {
    try {
        $report = Invoke-ComposePreflight -Root $RepositoryRoot
        foreach ($result in $report.Results) {
            Write-Output ('{0} {1}: {2}' -f $result.Status, $result.Check, $result.Detail)
        }
        exit $report.ExitCode
    }
    catch {
        Write-Output 'FAIL Preflight: Check could not complete; diagnostic details suppressed to protect secrets.'
        exit 1
    }
}
