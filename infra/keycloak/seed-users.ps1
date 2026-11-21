param()
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$privateDir = Join-Path $env:LOCALAPPDATA 'ZentroSecrets'
$credentialPath = Join-Path $privateDir 'seed-users.env'
$adminConfigPath = Join-Path $privateDir 'keycloak-admin.env'
$accounts = @(
  @{ Email = 'dispatcher@zentro.test'; First = 'Zentro'; Last = 'Dispatcher'; Phone = '+94770000001'; Role = 'DISPATCHER'; Permissions = @('DISPATCH_VIEW', 'DISPATCH_ASSIGN'); Key = 'ZENTRO_SEED_DISPATCHER_PASSWORD' },
  @{ Email = 'courier1@zentro.test'; First = 'Zentro'; Last = 'Courier 1'; Phone = '+94770000002'; Role = 'COURIER'; Key = 'ZENTRO_SEED_COURIER1_PASSWORD' },
  @{ Email = 'courier2@zentro.test'; First = 'Zentro'; Last = 'Courier 2'; Phone = '+94770000003'; Role = 'COURIER'; Key = 'ZENTRO_SEED_COURIER2_PASSWORD' }
)

function Stop-Safely([string]$Message) { throw $Message }
function Read-DotEnv([string]$Path) {
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') {
      $value = $Matches[2].Trim()
      if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
      $values[$Matches[1]] = $value
    }
  }
  return $values
}
function Assert-PrivatePath([string]$Path) {
  $resolved = [IO.Path]::GetFullPath($Path)
  foreach ($root in @($env:OneDrive, $env:OneDriveCommercial, $env:OneDriveConsumer) | Where-Object { $_ }) {
    $oneDrive = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
    if ($resolved.StartsWith($oneDrive, [StringComparison]::OrdinalIgnoreCase)) { Stop-Safely 'Private seed credentials must be outside OneDrive.' }
  }
}
function Assert-RestrictedAcl([string]$Path) {
  $acl = Get-Acl -LiteralPath $Path
  if (-not $acl.AreAccessRulesProtected) { Stop-Safely 'Seed credential ACL inheritance must be disabled.' }
  $rules = @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))
  $expected = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')
  if ($rules.Count -ne 3) { Stop-Safely 'Seed credential ACL must contain exactly owner, SYSTEM, and Administrators.' }
  foreach ($rule in $rules) {
    if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
        $rule.IdentityReference.Value -notin $expected -or
        (($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne [Security.AccessControl.FileSystemRights]::FullControl)) {
      Stop-Safely 'Seed credential ACL contains an unexpected access rule.'
    }
  }
  foreach ($sid in $expected) { if (-not ($rules | Where-Object { $_.IdentityReference.Value -eq $sid })) { Stop-Safely 'Seed credential ACL is missing a required principal.' } }
}

try {
  if (-not $env:LOCALAPPDATA -or -not (Test-Path -LiteralPath $privateDir -PathType Container) -or -not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) { Stop-Safely 'Private seed credential file is missing.' }
  Assert-PrivatePath $credentialPath
  Assert-RestrictedAcl $privateDir
  Assert-RestrictedAcl $credentialPath
  if (-not (Test-Path -LiteralPath $adminConfigPath -PathType Leaf)) { Stop-Safely 'Private local Keycloak admin configuration is missing.' }
  Assert-PrivatePath $adminConfigPath
  Assert-RestrictedAcl $adminConfigPath
  $admin = Read-DotEnv $adminConfigPath
  $private = Read-DotEnv $credentialPath
  $adminUser = $admin['KC_BOOTSTRAP_ADMIN_USERNAME']
  $adminPassword = $admin['KC_BOOTSTRAP_ADMIN_PASSWORD']
  if (-not $adminUser -or -not $adminPassword) { Stop-Safely 'Local Keycloak administrator configuration is incomplete.' }
  $baseUrl = if ($admin['KEYCLOAK_URL']) { $admin['KEYCLOAK_URL'].TrimEnd('/') } else { 'http://localhost:' + $(if ($admin['KC_HTTP_PORT']) { $admin['KC_HTTP_PORT'] } else { '8180' }) }
  $uri = [Uri]$baseUrl
  if ($uri.Scheme -ne 'http' -or $uri.Host -notin @('localhost', '127.0.0.1', '::1') -or $uri.Port -ne 8180) { Stop-Safely 'Seed workflow only permits local Keycloak at port 8180.' }
  $realm = if ($admin['KEYCLOAK_REALM']) { $admin['KEYCLOAK_REALM'] } else { 'zentro' }
  if ($realm -ne 'zentro') { Stop-Safely 'Seed workflow only permits the local zentro realm.' }
  $passwords = @{}
  foreach ($account in $accounts) {
    $value = $private[$account.Key]
    if (-not $value -or $value.Length -lt 32) { Stop-Safely 'A required private seed credential is missing or too short.' }
    $passwords[$account.Email] = $value
  }
  if (@($passwords.Values | Select-Object -Unique).Count -ne $accounts.Count) { Stop-Safely 'Seed account credentials must be independent.' }

  $tokenResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/realms/master/protocol/openid-connect/token" -ContentType 'application/x-www-form-urlencoded' -Body @{ grant_type='password'; client_id='admin-cli'; username=$adminUser; password=$adminPassword }
  $headers = @{ Authorization = "Bearer $($tokenResponse.access_token)" }
  if (-not $tokenResponse.access_token) { Stop-Safely 'Could not authenticate to local Keycloak.' }
  $api = "$baseUrl/admin/realms/$realm"
  foreach ($account in $accounts) {
    $escaped = [Uri]::EscapeDataString($account.Email)
    $users = @(Invoke-RestMethod -Method Get -Uri "$api/users?username=$escaped&exact=true" -Headers $headers)
    if ($users.Count) { Write-Output "$($account.Email): exists; left unchanged."; continue }

    $attributes = @{ phone = @($account.Phone) }
    if ($account.Permissions) { $attributes['permissions'] = @($account.Permissions) }
    $body = @{
      username = $account.Email; email = $account.Email; firstName = $account.First; lastName = $account.Last
      enabled = $true; emailVerified = $true; attributes = $attributes
      credentials = @(@{ type = 'password'; value = $passwords[$account.Email]; temporary = $false })
    } | ConvertTo-Json -Depth 8 -Compress
    Invoke-RestMethod -Method Post -Uri "$api/users" -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
    $created = @(Invoke-RestMethod -Method Get -Uri "$api/users?username=$escaped&exact=true" -Headers $headers)
    if ($created.Count -ne 1 -or -not $created[0].id) { Stop-Safely 'Created account could not be uniquely verified; stop for inspection.' }
    $role = Invoke-RestMethod -Method Get -Uri "$api/roles/$($account.Role)" -Headers $headers
    $mappingBody = ConvertTo-Json -InputObject @($role) -Depth 8 -Compress
    Invoke-RestMethod -Method Post -Uri "$api/users/$($created[0].id)/role-mappings/realm" -Headers $headers -ContentType 'application/json' -Body $mappingBody | Out-Null
    Write-Output "$($account.Email): created; credential was not displayed or saved by this script."
  }
}
catch {
  Write-Error 'Seed workflow stopped. No credential values or response bodies were written to output; inspect local Keycloak state before retrying.'
  exit 1
}
finally {
  Remove-Variable adminPassword, passwords, private, tokenResponse, headers, body, mappingBody -ErrorAction SilentlyContinue
}
