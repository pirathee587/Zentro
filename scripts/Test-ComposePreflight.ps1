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
            if ($composeAvailable) {
                foreach ($finding in (Get-ComposeSourceFindings -DockerPath $docker -File $file)) {
                    Add-Result $finding.Status ($file + ' / ' + $finding.Check) $finding.Detail
                }
            }
            else { Add-Result SKIP ($file + ' / source inspection') 'Requires Docker Compose.' }
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

function Invoke-PreflightDockerCapture {
    param([string]$DockerPath, [string[]]$Arguments)
    # Source-only model is held in memory, never sent to the report or persisted.
    $ErrorActionPreference = 'Stop'
    try {
        $output = & $DockerPath @Arguments 2>$null
        return [pscustomobject]@{ Success = ($LASTEXITCODE -eq 0); Text = ($output -join "`n") }
    }
    catch { return [pscustomobject]@{ Success = $false; Text = '' } }
}

function Get-ComposeSourceFindings {
    param([string]$DockerPath, [string]$File)
    $findings = New-Object 'System.Collections.Generic.List[object]'
    function Add-Finding([string]$Status, [string]$Check, [string]$Detail) {
        $findings.Add([pscustomobject]@{ Status = $Status; Check = $Check; Detail = $Detail })
    }
    try {
        $help = Invoke-PreflightDockerCapture $DockerPath @('compose', 'config', '--help')
        if (-not $help.Success -or $help.Text -notmatch '--no-env-resolution') {
            Add-Finding SKIP 'source inspection' 'Compose does not advertise the required source-only inspection flags.'
            return $findings
        }
        $capture = Invoke-PreflightDockerCapture $DockerPath @(
            'compose', '--env-file', 'NUL', '-f', $File, 'config', '--format', 'json',
            '--no-interpolate', '--no-env-resolution', '--no-normalize', '--no-path-resolution'
        )
        if (-not $capture.Success) {
            Add-Finding FAIL 'source inspection' 'Compose rejected the source model; diagnostics suppressed.'
            return $findings
        }
        $model = ConvertFrom-Json -InputObject $capture.Text -ErrorAction Stop
        if (-not $model.services -or $model.services -isnot [pscustomobject] -or
            @($model.services.PSObject.Properties).Count -eq 0) {
            throw 'Invalid service model'
        }
        foreach ($property in $model.services.PSObject.Properties) {
            $service = $property.Value
            # Compose validates service identifiers; do not echo arbitrary model strings.
            $label = 'service'
            if ($property.Name -match '^[a-zA-Z0-9][a-zA-Z0-9_.-]*$') { $label = $property.Name }
            $health = $service.healthcheck
            $test = @($health.test) -join ' '
            if ($health.disable -eq $true -or $test -match '^NONE(?:\s|$)') {
                Add-Finding FAIL ($label + ' healthcheck') 'Explicitly disabled; violates WARP.md section 18 health-check requirement.'
            }
            elseif ($test -and $test -notmatch '^\s*$') {
                Add-Finding PASS ($label + ' healthcheck') 'Compose declares a health test (WARP.md section 18); not executed.'
                if ($test -match '(?i)liveness' -and $test -notmatch '(?i)readiness') {
                    Add-Finding WARN ($label + ' readiness') 'Test is labelled liveness; dependency readiness is not established.'
                }
                elseif ($test -match '(?i)readiness' -and $test -notmatch '(?i)liveness') {
                    Add-Finding WARN ($label + ' liveness') 'Test is labelled readiness; separate liveness behavior requires verification.'
                }
                else { Add-Finding WARN ($label + ' probe semantics') 'Liveness/readiness distinction cannot be established from the command text.' }
            }
            else {
                Add-Finding WARN ($label + ' healthcheck') 'No Compose health test; image/Dockerfile inheritance and effective health require separate verification.'
                Add-Finding SKIP ($label + ' probe semantics') 'No Compose health test to classify.'
            }
            $dependencies = $service.depends_on
            if (-not $dependencies) {
                Add-Finding WARN ($label + ' dependencies') 'No startup dependency gates declared; required dependency readiness needs verification.'
            }
            elseif ($dependencies -is [pscustomobject]) {
                foreach ($dependency in $dependencies.PSObject.Properties) {
                    $condition = $dependency.Value.condition
                    if ($condition -eq 'service_healthy') {
                        $targetHealth = $model.services.($dependency.Name).healthcheck
                        $targetTest = @($targetHealth.test) -join ' '
                        if ($targetHealth.disable -eq $true -or $targetTest -match '^NONE(?:\s|$)') {
                            Add-Finding FAIL ($label + ' dependencies') 'Health-gated dependency explicitly disables its health check.'
                        }
                        else { Add-Finding WARN ($label + ' dependencies') 'Waits for service health; effective target probe and dependency readiness still require verification.' }
                    }
                    elseif ($condition -eq 'service_completed_successfully') {
                        Add-Finding WARN ($label + ' dependencies') 'Waits for successful completion, not ongoing service readiness.'
                    }
                    else { Add-Finding WARN ($label + ' dependencies') 'Waits only for container startup, not service health.' }
                }
            }
            else { Add-Finding WARN ($label + ' dependencies') 'Short dependency syntax waits only for container startup.' }
            if ($service.container_name) {
                Add-Finding WARN ($label + ' isolation') 'Explicit container name can collide across projects; project naming alone is insufficient.'
            }
            foreach ($port in @($service.ports)) {
                if ($null -eq $port) { continue }
                if (($port -is [string] -and $port -match ':') -or $port.published) {
                    Add-Finding WARN ($label + ' ports') 'Published host port may collide; availability and interpolation require isolated runtime verification.'
                }
            }
        }
        foreach ($section in @('networks', 'volumes')) {
            if ($model.$section) {
                foreach ($resource in $model.$section.PSObject.Properties) {
                    if ($resource.Value.name -or $resource.Value.external) {
                        Add-Finding WARN ($section + ' isolation') 'Explicit name or external resource may be shared across projects; verify isolation before startup.'
                    }
                }
            }
        }
    }
    catch {
        # Never echo JSON, YAML, commands, exception messages or captured output.
        $findings.Clear()
        Add-Finding FAIL 'source inspection' 'Source model could not be inspected safely; diagnostics suppressed.'
    }
    return $findings
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
