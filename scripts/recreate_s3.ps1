param(
  [Parameter(Mandatory)][string]$NewImageId,
  [Parameter(Mandatory)][string]$NewTag,
  [Parameter(Mandatory)][string]$SnapshotPath,
  [Parameter(Mandatory)][string]$SnapshotSha256,
  [Parameter(Mandatory)][switch]$ConfirmCutover
)
$ErrorActionPreference = 'Stop'
$Name = 'zentro-dispatch-courier-service'
$BackupName = 'zentro-dispatch-courier-service-pre-oauth'
$OldImageId = 'sha256:ea4719bfe6f41b2db81f86b6c519ba6051c2fbc1d2121c5c3a85d252bb9897ad'
$PrivateDir = Join-Path $env:LOCALAPPDATA 'ZentroSecrets'
$Credentials = Join-Path $PrivateDir 'zentro.env'
$TemporaryEnvFile = Join-Path $PrivateDir 's3-oauth.env'
$Verifier = Join-Path $PSScriptRoot 'compare_containers.py'
Import-Module (Join-Path $PSScriptRoot 'S3RecreateSafety.psm1') -Force

function Invoke-DockerJson([string[]]$Arguments) {
  $raw = & docker @Arguments 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Read-only Docker preflight failed; no credentials or daemon diagnostics emitted.' }
  try { return ($raw | ConvertFrom-Json -ErrorAction Stop) } catch { throw 'Docker returned invalid inspect data.' }
}
function Invoke-DockerMutation([string[]]$Arguments, [string]$Failure) {
  & docker @Arguments 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { throw $Failure }
}

try {
  if (-not $ConfirmCutover) { throw 'Explicit -ConfirmCutover is required; no Docker changes were performed.' }
  if ($NewImageId -notmatch '^sha256:[0-9a-f]{64}$') { throw 'New image ID must be a full immutable SHA-256 digest.' }
  if (-not (Test-Path -LiteralPath $SnapshotPath -PathType Leaf)) { throw 'Independent original S3 snapshot is missing.' }
  if (-not (Test-S3RestrictedAcl -Path $PrivateDir)) { throw 'Private credential directory ACL is not restrictive.' }
  if (-not (Test-S3PathOutsideOneDrive -Path $Credentials)) { throw 'Private credentials must be outside OneDrive.' }
  if (Test-Path -LiteralPath $TemporaryEnvFile) { throw 'Refusing stale temporary S3 env file.' }

  # The verifier reads one container and compares it to the saved JSON baseline.
  # It cannot accept a second live container name as its reference.
  & python -B $Verifier --snapshot $SnapshotPath --snapshot-sha256 $SnapshotSha256 --candidate $Name
  if ($LASTEXITCODE -ne 0) { throw 'Independent original S3 snapshot verification failed; cutover refused.' }

  $original = (Invoke-DockerJson @('inspect', $Name))[0]
  if ($original.Image -ne $OldImageId -or $original.State.Status -ne 'running') { throw 'Current S3 image or state differs from the approved baseline.' }
  $newResolved = & docker image inspect $NewTag --format '{{.Id}}' 2>$null
  if ($LASTEXITCODE -ne 0 -or $newResolved.Trim() -ne $NewImageId) { throw 'New image tag does not resolve to the supplied immutable image ID.' }
  if (& docker ps -a --filter "name=^/$BackupName$" --format '{{.ID}}' 2>$null) { throw 'Preserved S3 container name is already occupied.' }

  $dispatchSecret = Get-S3DispatchSecret -CredentialFile $Credentials
  $oldImage = (Invoke-DockerJson @('image', 'inspect', $OldImageId))[0]
  $imageDefaults = @($oldImage.Config.Env)
  $lines = @()
  foreach ($entry in $original.Config.Env) {
    if ($imageDefaults -contains $entry) { continue }
    if ($entry -like 'POD_CLIENT_SECRET=*') { $lines += "POD_CLIENT_SECRET=$dispatchSecret" }
    else { $lines += $entry }
  }
  foreach ($setting in @{
      SPRING_FLYWAY_ENABLED = 'false'
      SPRING_FLYWAY_VALIDATE_ON_MIGRATE = 'true'
      POD_WORKER_ENABLED = 'false'
      OUTBOX_ENABLED = 'false'
    }.GetEnumerator()) {
    $matches = @($lines | Where-Object { $_ -like ($setting.Key + '=*') })
    if ($matches.Count -ne 1 -or $matches[0] -cne ($setting.Key + '=' + $setting.Value)) { throw 'S3 safety configuration is missing, duplicated, or unsafe.' }
  }
  $secretLines = @($lines | Where-Object { $_ -like 'POD_CLIENT_SECRET=*' })
  if ($secretLines.Count -ne 1 -or $secretLines[0].Substring('POD_CLIENT_SECRET='.Length) -cne $dispatchSecret) { throw 'Dispatch service credential is missing or mismatched.' }

  Invoke-S3WithTemporaryEnvFile -Path $TemporaryEnvFile -Lines $lines -Action {
    param($envFile)
    Invoke-DockerMutation @('stop', '--time', '30', $Name) 'Could not stop the original S3 container.'
    Invoke-DockerMutation @('rename', $Name, $BackupName) 'Could not preserve the original S3 container.'
    Invoke-DockerMutation @('create', '--name', $Name, '--env-file', $envFile, '--restart', 'no', '--log-driver', 'local',
      '--network', 'zentro-backend', '--network-alias', 'dispatch-courier-service', '--network-alias', 'zentro-dispatch-courier-service',
      '-p', '127.0.0.1:8083:8083', $NewTag) 'Could not create the candidate S3 container.'
    Invoke-DockerMutation @('network', 'connect', '--alias', 'dispatch-courier-service', '--alias', 'zentro-dispatch-courier-service', 'keycloak_default', $Name) 'Could not attach candidate S3 to the Keycloak network.'
    Write-Output 'Candidate created but not started. Original S3 container and image are preserved.'
  }
}
catch {
  Write-Error 'S3 recreation stopped. Inspect state before any manual recovery; no automatic rollback is attempted.'
  exit 1
}
finally {
  Remove-Variable dispatchSecret, lines, original, oldImage, newResolved -ErrorAction SilentlyContinue
}
