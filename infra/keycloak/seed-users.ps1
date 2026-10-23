$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$envPath = Join-Path $scriptDir '.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Missing infra/keycloak/.env. Copy .env.example to .env first.' }

Get-Content -LiteralPath $envPath | ForEach-Object {
  $line = $_.Trim()
  if (-not $line -or $line.StartsWith('#') -or $line -notmatch '^([^=]+)=(.*)$') { return }
  $name = $Matches[1].Trim()
  $value = $Matches[2].Trim()
  if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
  [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$keycloakPort = if ($env:KC_HTTP_PORT) { $env:KC_HTTP_PORT } else { '8180' }
$baseUrl = if ($env:KEYCLOAK_URL) { $env:KEYCLOAK_URL.TrimEnd('/') } else { "http://localhost:$keycloakPort" }
$realm = if ($env:KEYCLOAK_REALM) { $env:KEYCLOAK_REALM } else { 'zentro' }
if (-not $env:KC_BOOTSTRAP_ADMIN_USERNAME -or -not $env:KC_BOOTSTRAP_ADMIN_PASSWORD) { throw 'Set the Keycloak bootstrap admin credentials in infra/keycloak/.env.' }

$tokenResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/realms/master/protocol/openid-connect/token" -ContentType 'application/x-www-form-urlencoded' -Body @{
  grant_type = 'password'; client_id = 'admin-cli'; username = $env:KC_BOOTSTRAP_ADMIN_USERNAME; password = $env:KC_BOOTSTRAP_ADMIN_PASSWORD
}
if (-not $tokenResponse.access_token) { throw 'Could not obtain a Keycloak admin token.' }
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

function Get-KeycloakUser([string]$Email) {
  $encoded = [Uri]::EscapeDataString($Email)
  $users = Get-KeycloakItems (Invoke-RestMethod -Method Get -Uri "$api/users?username=$encoded&exact=true" -Headers $headers)
  if ($users.Count -eq 0) { $users = Get-KeycloakItems (Invoke-RestMethod -Method Get -Uri "$api/users?email=$encoded&exact=true" -Headers $headers) }
  return $users | Select-Object -First 1
}

$seedDefinitions = @(
  @{ Email = 'dispatcher@zentro.test'; First = 'Zentro'; Last = 'Dispatcher'; Phone = '+94770000001'; Role = 'DISPATCHER' },
  @{ Email = 'courier1@zentro.test'; First = 'Zentro'; Last = 'Courier 1'; Phone = '+94770000002'; Role = 'COURIER' },
  @{ Email = 'courier2@zentro.test'; First = 'Zentro'; Last = 'Courier 2'; Phone = '+94770000003'; Role = 'COURIER' }
)
$results = [System.Collections.Generic.List[object]]::new()
$fileLines = [System.Collections.Generic.List[string]]::new()
$fileLines.Add("Email`tPassword`tRole")

foreach ($definition in $seedDefinitions) {
  $email = $definition.Email
  $user = Get-KeycloakUser $email
  if (-not $user) {
    $body = @{ username = $email; email = $email; firstName = $definition.First; lastName = $definition.Last; enabled = $true; emailVerified = $true; attributes = @{ phone = @($definition.Phone) } } | ConvertTo-Json -Depth 8
    Invoke-RestMethod -Method Post -Uri "$api/users" -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
    $user = Get-KeycloakUser $email
  }
  if (-not $user.id) { throw "Could not create or find Keycloak account $email." }

  $update = @{ id = $user.id; username = $email; email = $email; firstName = $definition.First; lastName = $definition.Last; enabled = $true; emailVerified = $true; attributes = @{ phone = @($definition.Phone) } } | ConvertTo-Json -Depth 8
  Invoke-RestMethod -Method Put -Uri "$api/users/$($user.id)" -Headers $headers -ContentType 'application/json' -Body $update | Out-Null

  $randomBytes = New-Object byte[] 24
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  $rng.GetBytes($randomBytes)
  $rng.Dispose()
  $password = [BitConverter]::ToString($randomBytes).Replace('-', '')
  $passwordBody = @{ type = 'password'; value = $password; temporary = $false } | ConvertTo-Json
  Invoke-RestMethod -Method Put -Uri "$api/users/$($user.id)/reset-password" -Headers $headers -ContentType 'application/json' -Body $passwordBody | Out-Null

  $role = Invoke-RestMethod -Method Get -Uri "$api/roles/$($definition.Role)" -Headers $headers
  $mappings = Get-KeycloakItems (Invoke-RestMethod -Method Get -Uri "$api/users/$($user.id)/role-mappings/realm" -Headers $headers)
  if (-not ($mappings | Where-Object { $_.name -eq $definition.Role })) {
    $mappingBody = ConvertTo-Json -InputObject @($role) -Depth 8 -Compress
    Invoke-RestMethod -Method Post -Uri "$api/users/$($user.id)/role-mappings/realm" -Headers $headers -ContentType 'application/json' -Body $mappingBody | Out-Null
  }
  $results.Add([pscustomobject]@{ Email = $email; Password = $password; Role = $definition.Role })
  $fileLines.Add("$email`t$password`t$($definition.Role)")
}

$credentialsPath = Join-Path $scriptDir 'seed-credentials.local.txt'
Set-Content -LiteralPath $credentialsPath -Value $fileLines -Encoding utf8
$results | Format-Table -AutoSize
Write-Output "Fresh credentials saved to $credentialsPath. Keep that ignored local file private."
