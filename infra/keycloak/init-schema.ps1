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

if (-not $env:KC_DB_PASSWORD) { throw 'Set KC_DB_PASSWORD in infra/keycloak/.env, then run this script again.' }
if (-not $env:KC_DB_URL -or -not $env:KC_DB_USERNAME) { throw 'KC_DB_URL and KC_DB_USERNAME must be set in infra/keycloak/.env.' }
$connection = $env:KC_DB_URL -replace '^jdbc:postgresql://', 'postgresql://'
$dbUri = [Uri]$connection
$schema = if ($env:KC_DB_SCHEMA) { $env:KC_DB_SCHEMA } else { 'keycloak' }
if ($schema -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw 'KC_DB_SCHEMA contains unsupported characters.' }
$port = if ($dbUri.IsDefaultPort) { 5432 } else { $dbUri.Port }
if ($port -ne 5432) { throw 'Use the Supabase session pooler on port 5432 for Keycloak.' }

$sql = "CREATE SCHEMA IF NOT EXISTS `"$schema`";"
docker run --rm -e "PGPASSWORD=$env:KC_DB_PASSWORD" postgres:16 psql "host=$($dbUri.Host) port=$port dbname=postgres user=$env:KC_DB_USERNAME sslmode=require" --set=ON_ERROR_STOP=1 --command=$sql
if ($LASTEXITCODE -ne 0) { throw 'Schema creation failed. Check Docker connectivity and the Supabase database settings.' }
Write-Output "Keycloak schema '$schema' is ready."
