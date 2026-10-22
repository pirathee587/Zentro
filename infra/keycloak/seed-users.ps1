$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$envFile = Join-Path $root '.env'
if (Test-Path -LiteralPath $envFile) {
  foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and $Matches[1] -notmatch '^#') {
      [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('"', "'"), 'Process')
    }
  }
}
$baseUrl = $env:KEYCLOAK_URL
if (-not $baseUrl) { $baseUrl = 'http://localhost:8180' }
$baseUrl = $baseUrl.TrimEnd('/')
$realm = $env:KEYCLOAK_REALM
if (-not $realm) { $realm = 'zentro' }
if (-not $env:KEYCLOAK_ADMIN -or -not $env:KEYCLOAK_ADMIN_PASSWORD) { throw 'Set KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD in infra/keycloak/.env.' }
$tokenResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/realms/master/protocol/openid-connect/token" -ContentType 'application/x-www-form-urlencoded' -Body @{ grant_type='password'; client_id='admin-cli'; username=$env:KEYCLOAK_ADMIN; password=$env:KEYCLOAK_ADMIN_PASSWORD }
$headers = @{ Authorization = "Bearer $($tokenResponse.access_token)" }
$adminBase = "$baseUrl/admin/realms/$realm"
function New-StrongPassword {
  $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%*+-_'
  $builder = [System.Text.StringBuilder]::new()
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $bytes = New-Object byte[] 1
  28 | ForEach-Object { $rng.GetBytes($bytes); [void]$builder.Append($alphabet[[int]($bytes[0] % $alphabet.Length)]) }
  $rng.Dispose()
  return $builder.ToString()
}
function Get-Role([string]$name) {
  try { return Invoke-RestMethod -Method Get -Uri "$adminBase/roles/$name" -Headers $headers }
  catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
    $body = @{ name=$name; description="Zentro $name role" } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$adminBase/roles" -Headers $headers -ContentType 'application/json' -Body $body
    return Invoke-RestMethod -Method Get -Uri "$adminBase/roles/$name" -Headers $headers
  }
}
function Upsert-SeedUser([string]$email, [string]$role) {
  $encoded = [uri]::EscapeDataString($email)
  $found = @(Invoke-RestMethod -Method Get -Uri "$adminBase/users?email=$encoded&exact=true" -Headers $headers)
  if ($found.Count -gt 0) { $user = $found[0]; $userId = $user.id }
  else {
    $userId = [guid]::NewGuid().ToString()
    $user = @{ id=$userId; username=$email; email=$email; enabled=$true; emailVerified=$true; firstName=($email.Split('@')[0]); lastName='Zentro' }
    Invoke-RestMethod -Method Post -Uri "$adminBase/users" -Headers $headers -ContentType 'application/json' -Body ($user | ConvertTo-Json)
    $found = @(Invoke-RestMethod -Method Get -Uri "$adminBase/users?email=$encoded&exact=true" -Headers $headers)
    if ($found.Count -eq 0) { throw "Keycloak did not return created user $email" }
    $userId = $found[0].id
  }
  if (-not $user.enabled -or -not $user.emailVerified) {
    $user.enabled = $true; $user.emailVerified = $true
    Invoke-RestMethod -Method Put -Uri "$adminBase/users/$userId" -Headers $headers -ContentType 'application/json' -Body ($user | ConvertTo-Json -Depth 8)
  }
  $password = New-StrongPassword
  Invoke-RestMethod -Method Put -Uri "$adminBase/users/$userId/reset-password" -Headers $headers -ContentType 'application/json' -Body (@{ type='password'; value=$password; temporary=$false } | ConvertTo-Json)
  $roleObject = Get-Role $role
  $current = @(Invoke-RestMethod -Method Get -Uri "$adminBase/users/$userId/role-mappings/realm" -Headers $headers)
  if ($current.name -notcontains $role) {
    Invoke-RestMethod -Method Post -Uri "$adminBase/users/$userId/role-mappings/realm" -Headers $headers -ContentType 'application/json' -Body (@($roleObject) | ConvertTo-Json -Depth 6)
  }
  return [pscustomobject]@{ Email=$email; Role=$role; Password=$password }
}
foreach ($roleName in @('DISPATCHER','COURIER')) { [void](Get-Role $roleName) }
$credentials = @(
  (Upsert-SeedUser -email 'dispatcher@zentro.test' -role 'DISPATCHER'),
  (Upsert-SeedUser -email 'courier1@zentro.test' -role 'COURIER'),
  (Upsert-SeedUser -email 'courier2@zentro.test' -role 'COURIER')
)
$credentialPath = Join-Path $root 'seed-credentials.local.txt'
$credentials | Format-Table -AutoSize | Out-String | Set-Content -LiteralPath $credentialPath -Encoding utf8
Write-Host "Seed credentials (also saved to $credentialPath):"
$credentials | Format-Table -AutoSize
