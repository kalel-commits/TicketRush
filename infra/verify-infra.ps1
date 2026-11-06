# Proves per-service DB isolation (permissions, not missing tables), MySQL version/time zone, Redis and Kafka.
# Usage: .\infra\verify-infra.ps1   (needs `docker compose up -d mysql redis kafka`; works on Windows PowerShell 5.1)
$ErrorActionPreference = 'Continue'
Set-Location (Join-Path $PSScriptRoot '..')
$envFile = if (Test-Path .env) { '.env' } else { '.env.example' }
$cfg = @{}
Get-Content $envFile | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $cfg[$Matches[1]] = $Matches[2] }

$script:fail = $false
function Pass($m) { Write-Output "PASS  $m" }
function Bad($m, $detail) { Write-Output "FAIL  $m"; if ($detail) { Write-Output "      $detail" }; $script:fail = $true }
function Sql($user, $pw, $stmts) {
    $out = & docker compose exec -T -e "MYSQL_PWD=$pw" mysql mysql "-u$user" -N -B -e $stmts 2>&1 | Out-String
    return @{ Out = $out.Trim(); Ok = ($LASTEXITCODE -eq 0) }
}

$pw = @{ user_svc = $cfg.USER_DB_PASSWORD; event_svc = $cfg.EVENT_DB_PASSWORD; booking_svc = $cfg.BOOKING_DB_PASSWORD; payment_svc = $cfg.PAYMENT_DB_PASSWORD }
$own = @{ user_svc = 'user_db'; event_svc = 'event_db'; booking_svc = 'booking_db'; payment_svc = 'payment_db' }
$neighbour = @{ user_svc = 'event_db'; event_svc = 'booking_db'; booking_svc = 'payment_db'; payment_svc = 'user_db' }
$schemas = 'user_db', 'event_db', 'booking_db', 'payment_db'
$root = $cfg.MYSQL_ROOT_PASSWORD

$fixture = ($schemas | ForEach-Object { "DROP TABLE IF EXISTS $_.isolation_probe; CREATE TABLE $_.isolation_probe(id INT PRIMARY KEY); INSERT INTO $_.isolation_probe VALUES (1);" }) -join ' '
$r = Sql root $root $fixture; if ($r.Ok) { Pass 'root created isolation_probe fixtures' } else { Bad 'root fixture' $r.Out }

$r = Sql root $root 'SELECT COUNT(*) FROM event_db.isolation_probe'
if ($r.Out -eq '1') { Pass 'root reads event_db.isolation_probe' } else { Bad 'root read fixture' $r.Out }

foreach ($u in 'user_svc', 'event_svc', 'booking_svc', 'payment_svc') {
    $r = Sql $u $pw[$u] "SELECT COUNT(*) FROM $($neighbour[$u]).isolation_probe"
    if ($r.Out -match 'ERROR 1142') { Pass "$u denied on $($neighbour[$u]) (ERROR 1142)" } else { Bad "$u on $($neighbour[$u]) not denied with 1142" $r.Out }
    $r = Sql $u $pw[$u] "CREATE TABLE $($own[$u]).rw_probe(id INT); DROP TABLE $($own[$u]).rw_probe;"
    if ($r.Ok) { Pass "$u can create/drop in $($own[$u])" } else { Bad "$u DDL in own schema" $r.Out }
}

$r = Sql user_svc $pw.user_svc 'SHOW DATABASES'
$dbs = $r.Out -split "`r?`n"
if (($dbs -contains 'user_db') -and -not ($dbs -contains 'event_db')) { Pass 'user_svc sees user_db, not event_db' } else { Bad 'user_svc schema visibility' $r.Out }

$r = Sql user_svc $pw.user_svc 'CREATE DATABASE rogue_db'
if ($r.Out -match 'ERROR 1044') { Pass 'user_svc cannot CREATE DATABASE (ERROR 1044)' } else { Bad 'user_svc CREATE DATABASE not denied with 1044' $r.Out }

$cleanup = ($schemas | ForEach-Object { "DROP TABLE IF EXISTS $_.isolation_probe;" }) -join ' '
$r = Sql root $root $cleanup; if ($r.Ok) { Pass 'fixtures dropped' } else { Bad 'cleanup' $r.Out }

$r = Sql root $root 'SELECT @@version, @@global.time_zone'
if ($r.Out -match '^8\.0\.\d+.*\+00:00$') { Pass "MySQL 8.0, UTC ($($r.Out))" } else { Bad 'MySQL version/time zone' $r.Out }

$out = (& docker compose exec -T redis redis-cli PING 2>&1 | Out-String).Trim()
if ($out -eq 'PONG') { Pass 'redis PING' } else { Bad 'redis' $out }

$out = & docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list 2>&1 | Out-String
if ($LASTEXITCODE -eq 0) { Pass 'kafka topics list' } else { Bad 'kafka' $out }

if (-not $script:fail) { Write-Output 'ALL INFRA CHECKS PASSED' } else { Write-Output 'INFRA CHECKS FAILED'; exit 1 }
