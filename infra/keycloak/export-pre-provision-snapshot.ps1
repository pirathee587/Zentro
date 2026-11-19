[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot '.env'),
    [string]$BackupRoot = (Join-Path $env:LOCALAPPDATA 'ZentroBackups'),
    [string]$KeycloakUrl,
    [string]$Realm
)

$ErrorActionPreference = 'Stop'

function Read-DotEnvValue([string]$Path, [string]$Name) {
    $line = Get-Content -LiteralPath $Path | Where-Object { $_ -match "^\s*$([regex]::Escape($Name))=" } | Select-Object -First 1
    if (-not $line) { return $null }
    $value = ($line -split '=', 2)[1].Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    return $value
}

if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    throw 'Keycloak configuration file was not found.'
}

$adminUser = Read-DotEnvValue $ConfigPath 'KC_BOOTSTRAP_ADMIN_USERNAME'
$adminPassword = Read-DotEnvValue $ConfigPath 'KC_BOOTSTRAP_ADMIN_PASSWORD'
if (-not $adminUser -or -not $adminPassword) {
    throw 'The existing Keycloak bootstrap admin username/password entries are required.'
}
if (-not $KeycloakUrl) { $KeycloakUrl = Read-DotEnvValue $ConfigPath 'KEYCLOAK_URL' }
if (-not $KeycloakUrl) { $KeycloakUrl = 'http://localhost:8180' }
if (-not $Realm) { $Realm = Read-DotEnvValue $ConfigPath 'KEYCLOAK_REALM' }
if (-not $Realm) { $Realm = 'zentro' }
$KeycloakUrl = $KeycloakUrl.TrimEnd('/')

$tokenResponse = Invoke-RestMethod -Method Post `
    -Uri "$KeycloakUrl/realms/master/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = $adminUser; password = $adminPassword }
$accessToken = [string]$tokenResponse.access_token
if (-not $accessToken) { throw 'Keycloak did not return an administrator access token.' }
$headers = @{ Authorization = "Bearer $accessToken" }
$realmPath = [Uri]::EscapeDataString($Realm)

try {
    # All requests below are GETs. Select only explicitly approved, non-secret fields.
    $realmInfo = Invoke-RestMethod -Method Get -Uri "$KeycloakUrl/admin/realms/$realmPath" -Headers $headers
    if ($realmInfo.realm -ne $Realm) { throw 'Keycloak returned a different realm than requested.' }

    $allClients = Invoke-RestMethod -Method Get -Uri "$KeycloakUrl/admin/realms/$realmPath/clients?first=0&max=1000" -Headers $headers
    $allRoles = Invoke-RestMethod -Method Get -Uri "$KeycloakUrl/admin/realms/$realmPath/roles" -Headers $headers
    $defaultRoleName = [string]$realmInfo.defaultRole.name
    if (-not $defaultRoleName) { $defaultRoleName = "default-roles-$Realm" }
    $escapedRole = [Uri]::EscapeDataString($defaultRoleName)
    $composites = Invoke-RestMethod -Method Get -Uri "$KeycloakUrl/admin/realms/$realmPath/roles/$escapedRole/composites" -Headers $headers

    $snapshot = [ordered]@{
        capturedAtUtc = [DateTime]::UtcNow.ToString('o')
        realm = $Realm
        defaultRole = $defaultRoleName
        existingClientIds = @($allClients | ForEach-Object { [string]$_.clientId } | Sort-Object -Unique)
        realmRoles = @($allRoles | ForEach-Object { [string]$_.name } | Sort-Object -Unique)
        defaultRoleComposites = @($composites | ForEach-Object { [string]$_.name } | Sort-Object -Unique)
    }

    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $destination = Join-Path $BackupRoot "keycloak-pre-provision-$stamp"
    New-Item -ItemType Directory -Path $destination -ErrorAction Stop | Out-Null
    $snapshotPath = Join-Path $destination 'snapshot.json'
    $json = $snapshot | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($snapshotPath, $json, [System.Text.UTF8Encoding]::new($false))

    $verified = Get-Content -LiteralPath $snapshotPath -Raw | ConvertFrom-Json
    if ($verified.realm -ne $Realm -or $null -eq $verified.existingClientIds -or $null -eq $verified.realmRoles -or $null -eq $verified.defaultRoleComposites) {
        throw 'Snapshot validation failed.'
    }
    if (-not (Test-Path -LiteralPath $snapshotPath -PathType Leaf)) { throw 'Snapshot file was not created.' }
    $null = Get-Content -LiteralPath $snapshotPath -Raw

    Write-Output "Snapshot saved and verified: $snapshotPath"
    Write-Output "Realm: $Realm"
    Write-Output "Client count: $(@($verified.existingClientIds).Count)"
    Write-Output "Realm role count: $(@($verified.realmRoles).Count)"
    Write-Output "Default-role composite count: $(@($verified.defaultRoleComposites).Count)"
}
finally {
    # Keep tokens and credentials out of output; clear local references promptly.
    $headers = $null
    $accessToken = $null
    $tokenResponse = $null
    $adminPassword = $null
    $adminUser = $null
}
