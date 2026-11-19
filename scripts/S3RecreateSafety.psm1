Set-StrictMode -Version Latest

function Test-S3PathOutsideOneDrive {
  param([Parameter(Mandatory)][string]$Path)
  $resolved = [IO.Path]::GetFullPath($Path)
  foreach ($root in @($env:OneDrive, $env:OneDriveCommercial, $env:OneDriveConsumer) | Where-Object { $_ }) {
    $prefix = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
    if ($resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { return $false }
  }
  return $true
}

function Test-S3RestrictedAcl {
  param([Parameter(Mandatory)][string]$Path)
  try {
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    if (-not $acl.AreAccessRulesProtected) { return $false }
    $rules = @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))
    $expected = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')
    if ($rules.Count -ne 3) { return $false }
    foreach ($rule in $rules) {
      if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
          $rule.IdentityReference.Value -notin $expected -or
          (($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne [Security.AccessControl.FileSystemRights]::FullControl)) { return $false }
    }
    foreach ($sid in $expected) { if (-not ($rules | Where-Object { $_.IdentityReference.Value -eq $sid })) { return $false } }
    return $true
  } catch { return $false }
}

function Set-S3RestrictedAcl {
  param([Parameter(Mandatory)][string]$Path)
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
  & icacls.exe $Path '/inheritance:r' '/grant:r' "$($identity):(F)" 'SYSTEM:(F)' 'Administrators:(F)' | Out-Null
  if ($LASTEXITCODE -ne 0 -or -not (Test-S3RestrictedAcl -Path $Path)) { throw 'Restricted ACL could not be established and verified.' }
}

function Get-S3EnvValue {
  param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Name)
  foreach ($line in [IO.File]::ReadAllLines($Path)) {
    if ($line -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$' -and $Matches[1] -eq $Name) {
      $value = $Matches[2].Trim()
      if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
      if (-not $value) { throw "Required private setting is missing: $Name" }
      return $value
    }
  }
  throw "Required private setting is missing: $Name"
}

function Get-S3DispatchSecret {
  param([Parameter(Mandatory)][string]$CredentialFile)
  $path = [IO.Path]::GetFullPath($CredentialFile)
  $directory = Split-Path -Parent $path
  if (-not (Test-Path -LiteralPath $directory -PathType Container) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Private S3 credential file or directory is missing.' }
  if (-not (Test-S3PathOutsideOneDrive -Path $path)) { throw 'Private S3 credential file must be outside OneDrive.' }
  if (-not (Test-S3RestrictedAcl -Path $directory) -or -not (Test-S3RestrictedAcl -Path $path)) { throw 'Private S3 credential ACL is not restrictive.' }
  $secret = Get-S3EnvValue -Path $path -Name 'ZENTRO_DISPATCH_SERVICE_CLIENT_SECRET'
  if ($secret.Length -lt 32) { throw 'Private Dispatch client credential is too short.' }
  return $secret
}

function New-S3TemporaryEnvFile {
  param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string[]]$Lines,
    [scriptblock]$AclSetter = { param($p) Set-S3RestrictedAcl -Path $p },
    [scriptblock]$AclVerifier = { param($p) Test-S3RestrictedAcl -Path $p }
  )
  if (Test-Path -LiteralPath $Path) { throw 'Refusing stale temporary S3 env file.' }
  $createdByCall = $false
  try {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $createdByCall = $true
    $stream.Dispose()
    & $AclSetter $Path
    if (-not (& $AclVerifier $Path)) { throw 'Temporary S3 env-file ACL verification failed.' }
    [IO.File]::WriteAllLines($Path, $Lines, [Text.UTF8Encoding]::new($false))
    if (-not (& $AclVerifier $Path)) { throw 'Temporary S3 env-file ACL changed after writing.' }
    return $Path
  } catch {
    if ($createdByCall -and (Test-Path -LiteralPath $Path)) { Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue }
    if ($createdByCall -and (Test-Path -LiteralPath $Path)) { throw 'Temporary S3 env-file cleanup could not be verified; secret values were suppressed.' }
    throw 'Temporary S3 env file could not be safely prepared; secret values were suppressed.'
  }
}

function Invoke-S3WithTemporaryEnvFile {
  param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string[]]$Lines,
    [Parameter(Mandatory)][scriptblock]$Action,
    [scriptblock]$AclSetter = { param($p) Set-S3RestrictedAcl -Path $p },
    [scriptblock]$AclVerifier = { param($p) Test-S3RestrictedAcl -Path $p }
  )
  if (Test-Path -LiteralPath $Path) { throw 'Refusing stale temporary S3 env file.' }
  $ownsFile = $false
  try {
    $null = New-S3TemporaryEnvFile -Path $Path -Lines $Lines -AclSetter $AclSetter -AclVerifier $AclVerifier
    $ownsFile = $true
    & $Action $Path
  } finally {
    if ($ownsFile -and (Test-Path -LiteralPath $Path)) { Remove-Item -LiteralPath $Path -Force -ErrorAction Stop }
    if ($ownsFile -and (Test-Path -LiteralPath $Path)) { throw 'Temporary S3 env-file cleanup could not be verified.' }
  }
}

Export-ModuleMember -Function Test-S3PathOutsideOneDrive, Test-S3RestrictedAcl, Set-S3RestrictedAcl, Get-S3EnvValue, Get-S3DispatchSecret, New-S3TemporaryEnvFile, Invoke-S3WithTemporaryEnvFile
