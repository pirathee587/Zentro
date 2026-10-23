$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$envPath = Join-Path $scriptDir '.env'
$realmPath = Join-Path $scriptDir 'realm-export.json'
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Missing infra/keycloak/.env.' }

Get-Content -LiteralPath $envPath | ForEach-Object {
  $line = $_.Trim()
  if (-not $line -or $line.StartsWith('#') -or $line -notmatch '^([^=]+)=(.*)$') { return }
  $name = $Matches[1].Trim()
  $value = $Matches[2].Trim()
  if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
  [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$baseUrl = if ($env:KEYCLOAK_URL) { $env:KEYCLOAK_URL.TrimEnd('/') } else { "http://localhost:$($env:KC_HTTP_PORT)" }
$realmName = if ($env:KEYCLOAK_REALM) { $env:KEYCLOAK_REALM } else { 'zentro' }
if (-not $env:KC_BOOTSTRAP_ADMIN_USERNAME -or -not $env:KC_BOOTSTRAP_ADMIN_PASSWORD) { throw 'Keycloak bootstrap admin credentials are missing from infra/keycloak/.env.' }
$admin = Invoke-RestMethod -Method Post -Uri "$baseUrl/realms/master/protocol/openid-connect/token" -ContentType 'application/x-www-form-urlencoded' -Body @{
  grant_type = 'password'; client_id = 'admin-cli'; username = $env:KC_BOOTSTRAP_ADMIN_USERNAME; password = $env:KC_BOOTSTRAP_ADMIN_PASSWORD
}
$headers = @{ Authorization = "Bearer $($admin.access_token)" }
$api = "$baseUrl/admin/realms/$realmName"
$desired = Get-Content -LiteralPath $realmPath -Raw | ConvertFrom-Json

# Update realm settings without deleting the realm or its existing user accounts.
$realm = Invoke-RestMethod -Method Get -Uri $api -Headers $headers
foreach ($setting in @('registrationAllowed','registrationEmailAsUsername','loginWithEmailAllowed','verifyEmail','bruteForceProtected','loginTheme','passwordPolicy','enabled')) {
  $realm.$setting = $desired.$setting
}
Invoke-RestMethod -Method Put -Uri $api -Headers $headers -ContentType 'application/json' -Body ($realm | ConvertTo-Json -Depth 80) | Out-Null

foreach ($roleName in @('CUSTOMER','MERCHANT','COURIER','DISPATCHER','SERVICE')) {
  try { $null = Invoke-RestMethod -Method Get -Uri "$api/roles/$roleName" -Headers $headers }
  catch {
    $role = $desired.roles.realm | Where-Object name -eq $roleName | Select-Object -First 1
    Invoke-RestMethod -Method Post -Uri "$api/roles" -Headers $headers -ContentType 'application/json' -Body ($role | ConvertTo-Json -Depth 20) | Out-Null
  }
}
$defaultRoleName = "default-roles-$realmName"
$defaultRole = Invoke-RestMethod -Method Get -Uri "$api/roles/$defaultRoleName" -Headers $headers
$defaultRoleComposites = Invoke-RestMethod -Method Get -Uri "$api/roles/$defaultRoleName/composites" -Headers $headers
if (-not ($defaultRoleComposites | Where-Object name -eq 'CUSTOMER')) {
  $customerRole = Invoke-RestMethod -Method Get -Uri "$api/roles/CUSTOMER" -Headers $headers
  $customerRoleBody = ConvertTo-Json -InputObject @($customerRole) -Depth 20 -Compress
  Invoke-RestMethod -Method Post -Uri "$api/roles/$defaultRoleName/composites" -Headers $headers -ContentType 'application/json' -Body $customerRoleBody | Out-Null
}

$clients = Invoke-RestMethod -Method Get -Uri "$api/clients?clientId=zentro-web" -Headers $headers
$client = $clients[0]
if (-not $client.id) { throw 'Keycloak client zentro-web was not found.' }
$client | Add-Member -MemberType NoteProperty -Name redirectUris -Value @($client.redirectUris) -Force
$client | Add-Member -MemberType NoteProperty -Name webOrigins -Value @($client.webOrigins) -Force
$client.redirectUris = @($client.redirectUris + @('http://localhost:5173/callback','http://localhost:5173/silent-callback','http://localhost:3000/callback','http://localhost:3000/silent-callback','http://localhost:5173/*','http://localhost:3000/*') | Select-Object -Unique)
$client.webOrigins = @($client.webOrigins + @('http://localhost:5173','http://localhost:3000') | Select-Object -Unique)
$client.publicClient = $true
$client.standardFlowEnabled = $true
$client.implicitFlowEnabled = $false
$client.directAccessGrantsEnabled = $false
$client | Add-Member -MemberType NoteProperty -Name attributes -Value $client.attributes -Force
$clientAttributes = @{}
if ($client.attributes) { foreach ($property in $client.attributes.PSObject.Properties) { $clientAttributes[$property.Name] = $property.Value } }
$clientAttributes['pkce.code.challenge.method'] = 'S256'
$client.attributes = $clientAttributes
Invoke-RestMethod -Method Put -Uri "$api/clients/$($client.id)" -Headers $headers -ContentType 'application/json' -Body ($client | ConvertTo-Json -Depth 80) | Out-Null

$mappers = Invoke-RestMethod -Method Get -Uri "$api/clients/$($client.id)/protocol-mappers/models" -Headers $headers
$roleMapper = @($mappers | Where-Object { $_.protocolMapper -eq 'oidc-usermodel-realm-role-mapper' })[0]
if ($roleMapper) {
  $mapperConfig = @{}
  foreach ($property in $roleMapper.config.PSObject.Properties) { $mapperConfig[$property.Name] = $property.Value }
  $mapperConfig['access.token.claim'] = 'true'
  $mapperConfig['id.token.claim'] = 'true'
  $mapperConfig['userinfo.token.claim'] = 'true'
  $roleMapper.config = $mapperConfig
  Invoke-RestMethod -Method Put -Uri "$api/clients/$($client.id)/protocol-mappers/models/$($roleMapper.id)" -Headers $headers -ContentType 'application/json' -Body ($roleMapper | ConvertTo-Json -Depth 30) | Out-Null
} else {
  throw 'The zentro-web realm role mapper was not found; update the client mapper before starting API login.'
}

$componentType = 'org.keycloak.userprofile.UserProfileProvider'
$components = Invoke-RestMethod -Method Get -Uri "$api/components?type=$([Uri]::EscapeDataString($componentType))" -Headers $headers
$profileComponent = $components[0]
$profileComponentConfig = $desired.components.$componentType[0].config
if (-not $profileComponent.id) { throw 'Keycloak declarative user-profile component was not found.' }
$profileComponent.config.'kc.user.profile.config' = @($profileComponentConfig.'kc.user.profile.config')
Invoke-RestMethod -Method Put -Uri "$api/components/$($profileComponent.id)" -Headers $headers -ContentType 'application/json' -Body ($profileComponent | ConvertTo-Json -Depth 80) | Out-Null

$verifiedRealm = Invoke-RestMethod -Method Get -Uri $api -Headers $headers
$verifiedClient = Invoke-RestMethod -Method Get -Uri "$api/clients/$($client.id)" -Headers $headers
$verifiedMappers = Invoke-RestMethod -Method Get -Uri "$api/clients/$($client.id)/protocol-mappers/models" -Headers $headers
$verifiedRoleMapper = @($verifiedMappers | Where-Object { $_.protocolMapper -eq 'oidc-usermodel-realm-role-mapper' })[0]
$verifiedProfileComponents = Invoke-RestMethod -Method Get -Uri "$api/components?type=$([Uri]::EscapeDataString($componentType))" -Headers $headers
$verifiedProfile = (($verifiedProfileComponents[0].config.'kc.user.profile.config'[0]) | ConvertFrom-Json)
$verifiedDefaultRoles = Invoke-RestMethod -Method Get -Uri "$api/roles/$defaultRoleName/composites" -Headers $headers
$requiredAttributes = @($verifiedProfile.attributes | Where-Object { $_.name -in @('firstName','lastName','phone') -and $_.required.roles -contains 'user' } | ForEach-Object name)
if (-not $verifiedRealm.registrationAllowed -or -not $verifiedRealm.registrationEmailAsUsername -or -not $verifiedRealm.loginWithEmailAllowed -or $verifiedRealm.verifyEmail -or -not $verifiedRealm.bruteForceProtected -or $verifiedRealm.loginTheme -ne 'zentro') { throw 'Live realm settings did not match the requested configuration.' }
if ($verifiedRoleMapper.config.PSObject.Properties['access.token.claim'].Value -ne 'true' -or $verifiedDefaultRoles.name -notcontains 'CUSTOMER') { throw 'Live role claims or default CUSTOMER role verification failed.' }
if ($requiredAttributes.Count -ne 3 -or $verifiedClient.redirectUris -notcontains 'http://localhost:5173/callback' -or $verifiedClient.webOrigins -notcontains 'http://localhost:5173') { throw 'Live client redirect or registration profile verification failed.' }
Write-Output "Updated realm '$realmName' in place. Existing Keycloak user accounts were preserved."
