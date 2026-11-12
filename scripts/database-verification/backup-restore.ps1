[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $BackupDirectory,
    [Parameter(Mandatory = $true)] [string[]] $RestoreTargets
)

$ErrorActionPreference = 'Stop'
$names = @('S1', 'S2', 'S3', 'S4', 'S5')
if ($RestoreTargets.Count -ne 5) { throw 'Provide exactly five disposable restore database URLs.' }
$sources = foreach ($name in $names) {
    $value = [Environment]::GetEnvironmentVariable("${name}_DB_URL")
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Missing ${name}_DB_URL." }
    $value
}
if (($sources | Sort-Object -Unique).Count -ne 5) { throw 'Source URLs must identify five distinct databases.' }
if (($RestoreTargets | Sort-Object -Unique).Count -ne 5) { throw 'Restore targets must identify five distinct disposable databases.' }
foreach ($target in $RestoreTargets) {
    if ($sources -contains $target) { throw 'A restore target must never be the same URL as a source database.' }
}
if (!(Get-Command pg_dump -ErrorAction SilentlyContinue) -or !(Get-Command pg_restore -ErrorAction SilentlyContinue) -or !(Get-Command psql -ErrorAction SilentlyContinue)) {
    throw 'Install PostgreSQL client tools (pg_dump, pg_restore and psql) and add them to PATH.'
}
New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
for ($i = 0; $i -lt 5; $i++) {
    $dump = Join-Path $BackupDirectory "$($names[$i].ToLowerInvariant()).dump"
    if (Test-Path -LiteralPath $dump) { throw "Refusing to overwrite existing backup: $dump" }
    $targetObjects = & psql --no-psqlrc --tuples-only --no-align --set=ON_ERROR_STOP=1 --dbname $RestoreTargets[$i] --command "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind IN ('r','p','v','m','S','f') AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%';"
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect restore target for $($names[$i])." }
    if ([int]$targetObjects.Trim() -ne 0) { throw "Restore target for $($names[$i]) is not empty; refusing restore." }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    & pg_dump --format=custom --no-owner --no-privileges --file $dump $sources[$i]
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed for $($names[$i])." }
    & pg_restore --list $dump | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Backup archive validation failed for $($names[$i])." }
    # No --clean: restore must not drop objects from the target. Use a fresh empty database.
    & pg_restore --exit-on-error --no-owner --no-privileges --dbname $RestoreTargets[$i] $dump
    if ($LASTEXITCODE -ne 0) { throw "Restore failed for $($names[$i]); check the disposable target and permissions." }
    $timer.Stop()
    [pscustomobject]@{ Service = $names[$i]; Backup = $dump; RestoreTarget = $RestoreTargets[$i]; ElapsedSeconds = [math]::Round($timer.Elapsed.TotalSeconds, 2) }
}
