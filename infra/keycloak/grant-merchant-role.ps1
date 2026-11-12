[CmdletBinding()]
param(
  [Parameter(Mandatory = $false)]
  [string]$Email,

  [Parameter(Mandatory = $false)]
  [string]$Subject,

  [Parameter(Mandatory = $false)]
  [switch]$Revoke
)

$ErrorActionPreference = 'Stop'

if (-not $Email -and -not $Subject) {
  throw 'Either -Email or -Subject must be provided.'
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$envPath = Join-Path $scriptDir '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
  throw 'Missing infra/keycloak/.env. Copy .env.example to .env first.'
}

Get-Content -LiteralPath $envPath | ForEach-Object {
  $line = $_.Trim()
  if (-not $line -or $line.StartsWith('#') -or $line -notmatch '^([^=]+)=(.*)$') { return }
  $name = $Matches[1].Trim()
  $value = $Matches[2].Trim()
  if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
    $value = $value.Substring(1, $value.Length - 2)
  }
  [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$keycloakPort = if ($env:KC_HTTP_PORT) { $env:KC_HTTP_PORT } else { '8180' }
$baseUrl = if ($env:KEYCLOAK_URL) { $env:KEYCLOAK_URL.TrimEnd('/') } else { "http://localhost:$keycloakPort" }
$realm = if ($env:KEYCLOAK_REALM) { $env:KEYCLOAK_REALM } else { 'zentro' }
if (-not $env:KC_BOOTSTRAP_ADMIN_USERNAME -or -not $env:KC_BOOTSTRAP_ADMIN_PASSWORD) {
  throw 'Set the Keycloak bootstrap admin credentials in infra/keycloak/.env.'
}

$tokenResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/realms/master/protocol/openid-connect/token" -ContentType 'application/x-www-form-urlencoded' -Body @{
  grant_type = 'password'
  client_id = 'admin-cli'
  username = $env:KC_BOOTSTRAP_ADMIN_USERNAME
  password = $env:KC_BOOTSTRAP_ADMIN_PASSWORD
}
if (-not $tokenResponse.access_token) {
  throw 'Could not obtain a Keycloak admin token.'
}
$headers = @{ Authorization = "Bearer $($tokenResponse.access_token)" }
$api = "$baseUrl/admin/realms/$realm"

function Get-KeycloakItems($Response) {
  $items = [System.Collections.Generic.List[object]]::new()
  foreach ($item in $Response) {
    if ($item -is [array]) { foreach ($nested in $item) { $items.Add($nested) } }
    elseif ($null -ne $item) { $items.Add($item) }
  }
  return $items.ToArray()
}

$targetUser = $null

if ($Subject) {
  try {
    $targetUser = Invoke-RestMethod -Method Get -Uri "$api/users/$Subject" -Headers $headers
  } catch {
    throw "Could not find Keycloak user with Subject ID '$Subject'."
  }
} else {
  $encoded = [Uri]::EscapeDataString($Email)
  $users = Get-KeycloakItems (Invoke-RestMethod -Method Get -Uri "$api/users?username=$encoded&exact=true" -Headers $headers)
  if ($users.Count -eq 0) {
    $users = Get-KeycloakItems (Invoke-RestMethod -Method Get -Uri "$api/users?email=$encoded&exact=true" -Headers $headers)
  }
  if ($users.Count -eq 0) {
    throw "Could not find Keycloak user with Email or Username '$Email'."
  }
  $targetUser = $users[0]
}

$userId = $targetUser.id
$userEmail = if ($targetUser.email) { $targetUser.email } else { $targetUser.username }

$merchantRole = Invoke-RestMethod -Method Get -Uri "$api/roles/MERCHANT" -Headers $headers
$currentMappings = Get-KeycloakItems (Invoke-RestMethod -Method Get -Uri "$api/users/$userId/role-mappings/realm" -Headers $headers)
$hasMerchantRole = [bool]($currentMappings | Where-Object { $_.name -eq 'MERCHANT' })

$rolePayload = ConvertTo-Json -InputObject @($merchantRole) -Depth 8 -Compress

if ($Revoke) {
  if ($hasMerchantRole) {
    Invoke-RestMethod -Method Delete -Uri "$api/users/$userId/role-mappings/realm" -Headers $headers -ContentType 'application/json' -Body $rolePayload | Out-Null
    Write-Output "Successfully revoked 'MERCHANT' role from $userEmail (Subject: $userId)."
  } else {
    Write-Output "User $userEmail (Subject: $userId) does not have the 'MERCHANT' role. No action taken."
  }
} else {
  if (-not $hasMerchantRole) {
    Invoke-RestMethod -Method Post -Uri "$api/users/$userId/role-mappings/realm" -Headers $headers -ContentType 'application/json' -Body $rolePayload | Out-Null
    Write-Output "Successfully granted 'MERCHANT' role to $userEmail (Subject: $userId)."
    Write-Output "Note: The user must refresh their token or sign in again to receive the updated role in their JWT."
  } else {
    Write-Output "User $userEmail (Subject: $userId) already possesses the 'MERCHANT' role."
  }
}
