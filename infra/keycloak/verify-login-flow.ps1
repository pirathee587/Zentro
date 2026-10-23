$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$credentialPath = Join-Path $scriptDir 'seed-credentials.local.txt'
if (-not (Test-Path -LiteralPath $credentialPath)) { throw 'Run seed-users.ps1 first. The ignored local credential file is required for this check.' }
$credential = Get-Content -LiteralPath $credentialPath | Select-Object -Skip 1 | Where-Object { $_ -match '^dispatcher@zentro\.test\t' } | Select-Object -First 1
if (-not $credential) { throw 'Dispatcher test credentials were not found.' }
$parts = $credential -split "`t"
$email, $password = $parts[0], $parts[1]

function New-PkcePair {
  $bytes = New-Object byte[] 32
  $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
  $generator.GetBytes($bytes)
  $generator.Dispose()
  $verifier = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
  $sha = [Security.Cryptography.SHA256]::Create()
  $challengeBytes = $sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier))
  $sha.Dispose()
  $challenge = [Convert]::ToBase64String($challengeBytes).TrimEnd('=').Replace('+','-').Replace('/','_')
  return @{ Verifier = $verifier; Challenge = $challenge }
}

function New-AuthorizeUrl([string]$Prompt, [string]$Email, [string]$Challenge, [string]$State) {
  $query = "client_id=zentro-web&response_type=code&redirect_uri=$([Uri]::EscapeDataString('http://localhost:5173/callback'))&scope=$([Uri]::EscapeDataString('openid profile email roles'))&state=$State&code_challenge=$Challenge&code_challenge_method=S256&prompt=$Prompt&login_hint=$([Uri]::EscapeDataString($Email))"
  if ($Prompt -eq 'create') { $query += '&kc_action=register' }
  return "http://localhost:8180/realms/zentro/protocol/openid-connect/auth?$query"
}

function Get-InputAttribute([string]$Tag, [string]$Name) {
  $escaped = [Regex]::Escape($Name)
  if ($Tag -match "\b$escaped=`"([^`"]*)`"") { return [Net.WebUtility]::HtmlDecode($Matches[1]) }
  return $null
}

# Check the actual Keycloak registration page fields without creating an extra account.
$registrationPkce = New-PkcePair
$registrationState = [Guid]::NewGuid().ToString('N')
$registration = Invoke-WebRequest -UseBasicParsing -Uri (New-AuthorizeUrl 'create' $email $registrationPkce.Challenge $registrationState)
if ($registration.StatusCode -ne 200 -or $registration.Content -notmatch 'name="firstName"' -or $registration.Content -notmatch 'name="lastName"' -or $registration.Content -notmatch 'name="phone"' -or $registration.Content -notmatch 'name="password-confirm"') {
  throw 'Keycloak registration page did not show required name, phone, and password confirmation fields.'
}
Write-Output 'Keycloak registration form: required name, phone, password, and confirm fields are present.'

# Complete a real authorization-code + PKCE sign-in through Keycloak using a cookie-backed HTTP session.
$pkce = New-PkcePair
$state = [Guid]::NewGuid().ToString('N')
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginPage = Invoke-WebRequest -UseBasicParsing -WebSession $session -Uri (New-AuthorizeUrl 'login' $email $pkce.Challenge $state)
if ($loginPage.Content -notmatch 'zentro-email-chip' -or $loginPage.Content -notmatch [Regex]::Escape($email) -or $loginPage.Content -notmatch '>Change</a>' -or $loginPage.Content -notmatch 'type="password"') {
  throw 'Keycloak login page did not show the hinted email chip, Change link, and password field.'
}
if ($loginPage.Content -match 'type="email"') { throw 'Keycloak unexpectedly asked for the email again after receiving login_hint.' }
$setCookieHeaders = @($loginPage.Headers['Set-Cookie'])
$setCookieNames = @($setCookieHeaders | ForEach-Object { ($_ -split '=',2)[0] })
Write-Output "Keycloak set-cookie names: $($setCookieNames -join ', '); retained cookie count: $($session.Cookies.Count)"

$formMatch = [Regex]::Match($loginPage.Content, '<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"')
if (-not $formMatch.Success) { throw 'Could not find the Keycloak login form action.' }
$formAction = [Net.WebUtility]::HtmlDecode($formMatch.Groups[1].Value)
$formValues = @{}
foreach ($tag in [Regex]::Matches($loginPage.Content, '<input\b[^>]*>')) {
  $name = Get-InputAttribute $tag.Value 'name'
  if ($name -and $name -ne 'password') { $formValues[$name] = Get-InputAttribute $tag.Value 'value' }
}
$formValues['username'] = $email
$formValues['password'] = $password
$authenticatedPage = Invoke-WebRequest -UseBasicParsing -WebSession $session -Method Post -Uri $formAction -ContentType 'application/x-www-form-urlencoded' -Body $formValues -MaximumRedirection 10
$callbackUri = [Uri]$authenticatedPage.BaseResponse.ResponseUri
$callbackQuery = [System.Web.HttpUtility]::ParseQueryString($callbackUri.Query)
$code = $callbackQuery['code']
if (-not $code -or $callbackQuery['state'] -ne $state) { throw 'Keycloak did not return a matching authorization code and state.' }

$token = Invoke-RestMethod -Method Post -Uri 'http://localhost:8180/realms/zentro/protocol/openid-connect/token' -ContentType 'application/x-www-form-urlencoded' -Body @{
  grant_type = 'authorization_code'; client_id = 'zentro-web'; code = $code; redirect_uri = 'http://localhost:5173/callback'; code_verifier = $pkce.Verifier
}
$jwtPayload = $token.access_token.Split('.')[1].Replace('-','+').Replace('_','/')
switch ($jwtPayload.Length % 4) { 2 { $jwtPayload += '==' } 3 { $jwtPayload += '=' } }
$claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($jwtPayload)) | ConvertFrom-Json
$audiences = @($claims.aud)
$realmRoles = @($claims.realm_access.roles)
if ($claims.iss -ne 'http://localhost:8180/realms/zentro' -or $audiences -notcontains 'zentro-api' -or $realmRoles -notcontains 'DISPATCHER') {
  throw 'The access token did not contain the expected issuer, zentro-api audience, and DISPATCHER role.'
}
Write-Output 'OIDC sign-in: authorization code + PKCE passed; issuer, API audience, and DISPATCHER role are present.'

$headers = @{ Authorization = "Bearer $($token.access_token)" }
foreach ($path in @('/api/v1/users/me','/api/v1/admin/users')) {
  try { $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080$path" -Headers $headers -MaximumRedirection 0; $status = [int]$response.StatusCode }
  catch { $status = [int]$_.Exception.Response.StatusCode }
  if ($status -eq 401 -or ($path -eq '/api/v1/admin/users' -and $status -eq 403)) { throw "Gateway rejected the valid token or DISPATCHER role at $path (HTTP $status)." }
  if ($status -ge 500) { Write-Output "Gateway accepted the token at $path; downstream service is unavailable (HTTP $status)." }
  else { Write-Output "Gateway response at $path`: HTTP $status." }
}
