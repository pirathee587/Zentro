$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'S3RecreateSafety.psm1') -Force
$root = Join-Path ([IO.Path]::GetTempPath()) ('zentro-s3-safety-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$passed = 0
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; $script:passed++ }
$okAcl = { param($p) $true }
$okAclSetter = { param($p) }
try {
  $failurePath = Join-Path $root 'cleanup.env'
  try { Invoke-S3WithTemporaryEnvFile -Path $failurePath -Lines @('KEY=value') -AclSetter $okAclSetter -AclVerifier $okAcl -Action { param($p) throw 'synthetic failure' } } catch { }
  Assert (-not (Test-Path -LiteralPath $failurePath)) 'Temporary env file was left after action failure.'

  $aclPath = Join-Path $root 'acl-failure.env'
  $badAcl = { param($p) throw 'synthetic ACL failure' }
  try { Invoke-S3WithTemporaryEnvFile -Path $aclPath -Lines @('KEY=value') -AclSetter $badAcl -AclVerifier $okAcl -Action { param($p) } } catch { }
  Assert (-not (Test-Path -LiteralPath $aclPath)) 'Temporary env file was left after ACL failure.'

  $encodingPath = Join-Path $root 'encoding.env'
  $syntheticSecret = ('synthetic-' + ('x' * 64))
  $output = Invoke-S3WithTemporaryEnvFile -Path $encodingPath -Lines @("POD_CLIENT_SECRET=$syntheticSecret") -AclSetter $okAclSetter -AclVerifier $okAcl -Action {
    param($p)
    $bytes = [IO.File]::ReadAllBytes($p)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { throw 'UTF-8 BOM present' }
    'action completed'
  }
  Assert ($output -eq 'action completed') 'Temporary env action result was unexpected.'
  Assert (-not (Test-Path -LiteralPath $encodingPath)) 'Temporary env file was not removed after success.'
  Assert (-not ($output -match [regex]::Escape($syntheticSecret))) 'Secret value appeared in output.'

  $stalePath = Join-Path $root 'stale.env'
  [IO.File]::WriteAllText($stalePath, 'stale')
  $staleRefused = $false
  try { Invoke-S3WithTemporaryEnvFile -Path $stalePath -Lines @('KEY=value') -AclSetter $okAclSetter -AclVerifier $okAcl -Action { param($p) } }
  catch { $staleRefused = $true }
  Assert $staleRefused 'Stale file was accepted.'
  Assert (Test-Path -LiteralPath $stalePath) 'Stale env file was deleted instead of refused.'

  $missingPath = Join-Path $root 'missing.env'
  [IO.File]::WriteAllText($missingPath, 'OTHER_VALUE=present')
  try { $null = Get-S3EnvValue -Path $missingPath -Name 'ZENTRO_DISPATCH_SERVICE_CLIENT_SECRET'; throw 'Missing credential was accepted.' } catch { Assert ($_.Exception.Message -match 'Required private setting is missing') 'Missing credential failed ambiguously.' }

  Write-Output "PASS: $passed S3 temporary-file safety checks"
} finally {
  Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
