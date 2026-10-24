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

# Configure Google as a Keycloak broker when credentials are supplied locally.
if ([bool]$env:GOOGLE_CLIENT_ID -xor [bool]$env:GOOGLE_CLIENT_SECRET) {
  throw 'Set both GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in infra/keycloak/.env.'
}
if ($env:GOOGLE_CLIENT_ID -and $env:GOOGLE_CLIENT_SECRET) {
  $googleBody = @{
    alias = 'google'
    displayName = 'Google'
    providerId = 'google'
    enabled = $true
    trustEmail = $false
    storeToken = $false
    addReadTokenRoleOnCreate = $false
    authenticateByDefault = $false
    linkOnly = $false
    firstBrokerLoginFlowAlias = 'first broker login'
    config = @{
      clientId = $env:GOOGLE_CLIENT_ID
      clientSecret = $env:GOOGLE_CLIENT_SECRET
      defaultScope = 'openid profile email'
      syncMode = 'IMPORT'
    }
  }
  $googleProvider = $null
  try {
    $googleProvider = Invoke-RestMethod -Method Get -Uri "$api/identity-provider/instances/google" -Headers $headers
  } catch {
    $statusCode = [int]$_.Exception.Response.StatusCode
    if ($statusCode -ne 404) { throw }
  }
  if ($null -ne $googleProvider) {
    Invoke-RestMethod -Method Put -Uri "$api/identity-provider/instances/google" -Headers $headers -ContentType 'application/json' -Body ($googleBody | ConvertTo-Json -Depth 20) | Out-Null
  } else {
    Invoke-RestMethod -Method Post -Uri "$api/identity-provider/instances" -Headers $headers -ContentType 'application/json' -Body ($googleBody | ConvertTo-Json -Depth 20) | Out-Null
  }
  Write-Output 'Configured the Google identity provider in Keycloak.'
} else {
  Write-Output 'Google provider unchanged: set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in infra/keycloak/.env to enable Google sign-in.'
}

# Skip the first-login profile review after a successful Google identity response.
$brokerExecutions = Invoke-RestMethod -Method Get -Uri "$api/authentication/flows/first%20broker%20login/executions" -Headers $headers
$reviewProfileExecution = $brokerExecutions | Where-Object providerId -eq 'idp-review-profile' | Select-Object -First 1
if (-not $reviewProfileExecution.authenticationConfig) { throw 'First broker login Review Profile configuration was not found.' }
$reviewConfigUri = "$api/authentication/config/$($reviewProfileExecution.authenticationConfig)"
$reviewProfileConfig = Invoke-RestMethod -Method Get -Uri $reviewConfigUri -Headers $headers
$reviewProfileConfig.config.'update.profile.on.first.login' = 'off'
Invoke-RestMethod -Method Put -Uri $reviewConfigUri -Headers $headers -ContentType 'application/json' -Body ($reviewProfileConfig | ConvertTo-Json -Depth 20) | Out-Null
$verifiedReviewConfig = Invoke-RestMethod -Method Get -Uri $reviewConfigUri -Headers $headers
if ($verifiedReviewConfig.config.'update.profile.on.first.login' -ne 'off') { throw 'Keycloak still prompts users to review profile details on first sign-in.' }

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
$audienceMapper = @($mappers | Where-Object { $_.name -eq 'zentro-api audience' -or ($_.protocolMapper -eq 'oidc-audience-mapper' -and ($_.config.'included.client.audience' -eq 'zentro-api' -or $_.config.'included.custom.audience' -eq 'zentro-api')) })[0]
$audienceMapperBody = @{
  name = 'zentro-api audience'
  protocol = 'openid-connect'
  protocolMapper = 'oidc-audience-mapper'
  consentRequired = $false
  config = @{
    'included.client.audience' = ''
    'included.custom.audience' = 'zentro-api'
    'id.token.claim' = 'false'
    'access.token.claim' = 'true'
  }
}
if ($audienceMapper) {
  $audienceMapper.name = $audienceMapperBody.name
  $audienceMapper.protocol = $audienceMapperBody.protocol
  $audienceMapper.protocolMapper = $audienceMapperBody.protocolMapper
  $audienceMapper.consentRequired = $audienceMapperBody.consentRequired
  $audienceMapper.config = $audienceMapperBody.config
  Invoke-RestMethod -Method Put -Uri "$api/clients/$($client.id)/protocol-mappers/models/$($audienceMapper.id)" -Headers $headers -ContentType 'application/json' -Body ($audienceMapper | ConvertTo-Json -Depth 30) | Out-Null
} else {
  Invoke-RestMethod -Method Post -Uri "$api/clients/$($client.id)/protocol-mappers/models" -Headers $headers -ContentType 'application/json' -Body ($audienceMapperBody | ConvertTo-Json -Depth 30) | Out-Null
}
$mappers = Invoke-RestMethod -Method Get -Uri "$api/clients/$($client.id)/protocol-mappers/models" -Headers $headers
$audienceMapper = @($mappers | Where-Object { $_.name -eq 'zentro-api audience' -or ($_.protocolMapper -eq 'oidc-audience-mapper' -and ($_.config.'included.client.audience' -eq 'zentro-api' -or $_.config.'included.custom.audience' -eq 'zentro-api')) })[0]
if (-not $audienceMapper -or $audienceMapper.config.'included.client.audience' -or $audienceMapper.config.'included.custom.audience' -ne 'zentro-api' -or $audienceMapper.config.'access.token.claim' -ne 'true') {
  throw 'The zentro-api audience mapper could not be installed on zentro-web.'
}
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

# Admin access tokens can expire during the in-place realm updates above. Refresh before verification.
$admin = Invoke-RestMethod -Method Post -Uri "$baseUrl/realms/master/protocol/openid-connect/token" -ContentType 'application/x-www-form-urlencoded' -Body @{
  grant_type = 'password'; client_id = 'admin-cli'; username = $env:KC_BOOTSTRAP_ADMIN_USERNAME; password = $env:KC_BOOTSTRAP_ADMIN_PASSWORD
}
$headers = @{ Authorization = "Bearer $($admin.access_token)" }

$verifiedRealm = Invoke-RestMethod -Method Get -Uri $api -Headers $headers
$verifiedClient = Invoke-RestMethod -Method Get -Uri "$api/clients/$($client.id)" -Headers $headers
$verifiedMappers = Invoke-RestMethod -Method Get -Uri "$api/clients/$($client.id)/protocol-mappers/models" -Headers $headers
$verifiedRoleMapper = @($verifiedMappers | Where-Object { $_.protocolMapper -eq 'oidc-usermodel-realm-role-mapper' })[0]
$verifiedAudienceMapper = @($verifiedMappers | Where-Object { $_.name -eq 'zentro-api audience' -or ($_.protocolMapper -eq 'oidc-audience-mapper' -and ($_.config.'included.client.audience' -eq 'zentro-api' -or $_.config.'included.custom.audience' -eq 'zentro-api')) })[0]
$verifiedProfileComponents = Invoke-RestMethod -Method Get -Uri "$api/components?type=$([Uri]::EscapeDataString($componentType))" -Headers $headers
$verifiedProfile = (($verifiedProfileComponents[0].config.'kc.user.profile.config'[0]) | ConvertFrom-Json)
$verifiedDefaultRoles = Invoke-RestMethod -Method Get -Uri "$api/roles/$defaultRoleName/composites" -Headers $headers
$verifiedGoogleProvider = if ($env:GOOGLE_CLIENT_ID -and $env:GOOGLE_CLIENT_SECRET) { Invoke-RestMethod -Method Get -Uri "$api/identity-provider/instances/google" -Headers $headers } else { $null }
$requiredAttributes = @($verifiedProfile.attributes | Where-Object { $_.name -in @('firstName','lastName','phone') -and $_.required.roles -contains 'user' } | ForEach-Object name)
if (-not $verifiedRealm.registrationAllowed -or -not $verifiedRealm.registrationEmailAsUsername -or -not $verifiedRealm.loginWithEmailAllowed -or $verifiedRealm.verifyEmail -or -not $verifiedRealm.bruteForceProtected -or $verifiedRealm.loginTheme -ne 'zentro') { throw 'Live realm settings did not match the requested configuration.' }
if ($verifiedRoleMapper.config.PSObject.Properties['access.token.claim'].Value -ne 'true' -or $verifiedDefaultRoles.name -notcontains 'CUSTOMER') { throw 'Live role claims or default CUSTOMER role verification failed.' }
if (-not $verifiedAudienceMapper -or $verifiedAudienceMapper.config.'included.client.audience' -or $verifiedAudienceMapper.config.'included.custom.audience' -ne 'zentro-api' -or $verifiedAudienceMapper.config.'access.token.claim' -ne 'true') { throw 'Live zentro-api audience mapper verification failed.' }
if ($verifiedGoogleProvider -and (-not $verifiedGoogleProvider.enabled -or $verifiedGoogleProvider.providerId -ne 'google')) { throw 'Live Google identity provider verification failed.' }
if ($requiredAttributes.Count -ne 0 -or $verifiedClient.redirectUris -notcontains 'http://localhost:5173/callback' -or $verifiedClient.webOrigins -notcontains 'http://localhost:5173') { throw 'Live client redirect or optional registration profile verification failed.' }
Write-Output "Updated realm '$realmName' in place. Existing Keycloak user accounts were preserved."
Write-Output 'Google first-login profile review is disabled; name and phone are optional.'
