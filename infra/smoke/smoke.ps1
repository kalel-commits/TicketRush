# Plan 1 "Done when": end-to-end smoke through the gateway. Works on Windows PowerShell 5.1 and PowerShell 7.
# Usage: .\infra\smoke\smoke.ps1
Set-Location (Join-Path $PSScriptRoot '..\..')
$GW = 'http://localhost:8080'
$script:fail = $false
function Pass($m) { Write-Output "PASS  $m" }
function Bad($m, $d) { Write-Output "FAIL  $m"; if ($d) { Write-Output "      $d" }; $script:fail = $true }

# Returns @{ Status = <int>; Body = <string> } without throwing on 4xx/5xx (5.1 has no -SkipHttpErrorCheck)
function Http($method, $url, $body = $null, $token = $null, $accept = $null) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    if ($accept) { $headers['Accept'] = $accept }
    try {
        $p = @{ Method = $method; Uri = $url; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 10 }
        if ($body) { $p['Body'] = $body; $p['ContentType'] = 'application/json' }
        $r = Invoke-WebRequest @p
        $content = $r.Content
        # PS 5.1 returns byte[] for content types it doesn't know (e.g. application/vnd.spring-boot.actuator.v3+json)
        if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
        return @{ Status = [int]$r.StatusCode; Body = $content }
    } catch {
        if ($_.Exception.Response) { return @{ Status = [int]$_.Exception.Response.StatusCode; Body = '' } }
        return @{ Status = 0; Body = $_.Exception.Message }
    }
}

for ($i = 0; $i -lt 36; $i++) { if ((Http GET "$GW/actuator/health").Body -match '"UP"') { break }; Start-Sleep 5 }
if ((Http GET "$GW/actuator/health").Body -match '"UP"') { Pass 'gateway health UP' } else { Bad 'gateway health'; exit 1 }

for ($i = 0; $i -lt 24; $i++) { if ((Http GET 'http://localhost:8761/eureka/apps/USER-SERVICE' -accept 'application/json').Status -eq 200) { break }; Start-Sleep 5 }
if ((Http GET 'http://localhost:8761/eureka/apps/USER-SERVICE' -accept 'application/json').Status -eq 200) { Pass 'USER-SERVICE registered in Eureka' } else { Bad 'USER-SERVICE not in Eureka' }

$email = "smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())-ps@example.com"
$body = "{`"email`":`"$email`",`"password`":`"Smoke#12345`"}"
for ($i = 0; $i -lt 24; $i++) { $r = Http POST "$GW/api/users/register" $body; if ($r.Status -eq 201) { break }; Start-Sleep 5 }
if ($r.Status -eq 201) { Pass 'register -> 201' } else { Bad 'register' "got $($r.Status)" }

$login = Http POST "$GW/api/users/login" $body
$token = ($login.Body | ConvertFrom-Json).accessToken
if ($token) {
    $p = $token.Split('.')[1].Replace('-', '+').Replace('_', '/'); while ($p.Length % 4) { $p += '=' }
    $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json
    if ($claims.iss -eq 'ticketrush-user-service' -and @($claims.aud) -contains 'ticketrush-api' -and $claims.token_use -eq 'user') { Pass 'login -> token with iss/aud/token_use' } else { Bad 'token claims' ($claims | ConvertTo-Json -Compress) }
} else { Bad 'login' $login.Body }

$me = Http GET "$GW/api/users/me" -token $token
if ($me.Status -eq 200 -and ($me.Body | ConvertFrom-Json).email -eq $email) { Pass '/api/users/me returns the user' } else { Bad '/me' $me.Body }

if ((Http GET "$GW/api/users/me").Status -eq 401) { Pass '/me without token -> 401' } else { Bad '/me without token' }
if ((Http GET "$GW/internal/anything" -token $token).Status -eq 403) { Pass '/internal/** -> 403' } else { Bad '/internal/**' }
if ((Http POST "$GW/api/events" '{}' $token).Status -eq 403) { Pass 'POST /api/events as USER -> 403' } else { Bad 'event write as USER' }
foreach ($port in 8081, 8082, 8085, 8090) {
    if ((Http GET "http://localhost:$port/actuator/health").Status -eq 0) { Pass "port $port not published" } else { Bad "port $port is reachable from the host" }
}

# --- event-service (plan 2) ---
$cfg = @{}
if (Test-Path .env) { Get-Content .env | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $cfg[$Matches[1]] = $Matches[2] } }
$adminEmail = if ($cfg.ADMIN_EMAIL) { $cfg.ADMIN_EMAIL } else { 'admin@ticketrush.dev' }
$adminPassword = if ($cfg.ADMIN_PASSWORD) { $cfg.ADMIN_PASSWORD } else { 'Admin#12345' }
$adminLogin = Http POST "$GW/api/users/login" "{`"email`":`"$adminEmail`",`"password`":`"$adminPassword`"}"
$adminToken = if ($adminLogin.Status -eq 200) { ($adminLogin.Body | ConvertFrom-Json).accessToken } else { $null }
if ($adminToken) { Pass 'admin login' } else { Bad 'admin login' $adminLogin.Body }

# sale opened 5 min ago: the Docker VM clock can lag the host by seconds, so "now" here may still be the future there
$now = [DateTime]::UtcNow.AddMinutes(-5).ToString('yyyy-MM-ddTHH:mm:ssZ'); $start = [DateTime]::UtcNow.AddDays(30).ToString('yyyy-MM-ddTHH:mm:ssZ')
$eventBody = "{`"name`":`"Smoke Show PS`",`"venue`":`"Arena`",`"startsAt`":`"$start`",`"saleOpensAt`":`"$now`",`"priceCents`":2500,`"rows`":2,`"seatsPerRow`":3}"
for ($i = 0; $i -lt 24; $i++) { $created = Http POST "$GW/api/events" $eventBody $adminToken; if ($created.Status -eq 201) { break }; Start-Sleep 5 }
$eventId = if ($created.Status -eq 201) { ($created.Body | ConvertFrom-Json).id } else { $null }
if ($eventId) { Pass "admin creates event $eventId -> 201" } else { Bad 'create event' "status $($created.Status)" }

$details = Http GET "$GW/api/events/$eventId"
if ($details.Status -eq 200 -and ($details.Body | ConvertFrom-Json).availableSeats -eq 6) { Pass 'event details show 6 available seats (anonymous read)' } else { Bad 'event details' $details.Body }
$seats = Http GET "$GW/api/events/$eventId/seats"
# PS 5.1 emits a JSON array as ONE pipeline object, so @(... | ConvertFrom-Json) would count 1
$seatList = if ($seats.Status -eq 200) { ConvertFrom-Json $seats.Body } else { @() }
if ($seats.Status -eq 200 -and $seatList.Count -eq 6) { Pass 'seat map has 6 seats' } else { Bad 'seat map' $seats.Body }

# --- booking-service (plan 3) ---
function HttpH($method, $url, $body, $token, $headers) {
    try {
        $h = @{ Authorization = "Bearer $token" }; foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] }
        $p = @{ Method = $method; Uri = $url; Headers = $h; UseBasicParsing = $true; TimeoutSec = 10 }
        if ($body) { $p['Body'] = $body; $p['ContentType'] = 'application/json' }
        $r = Invoke-WebRequest @p
        $c = $r.Content; if ($c -is [byte[]]) { $c = [Text.Encoding]::UTF8.GetString($c) }
        return @{ Status = [int]$r.StatusCode; Body = $c }
    } catch {
        if ($_.Exception.Response) { return @{ Status = [int]$_.Exception.Response.StatusCode; Body = '' } }
        return @{ Status = 0; Body = $_.Exception.Message }
    }
}
function SeatStatus($seatId) { ((ConvertFrom-Json (Http GET "$GW/api/events/$eventId/seats").Body) | Where-Object { $_.id -eq $seatId }).status }

$seatId = $seatList[0].id
$bookBody = "{`"eventId`":$eventId,`"seatIds`":[$seatId]}"
# fresh key per attempt: a replay would only repeat a stored failure
for ($i = 0; $i -lt 24; $i++) { $key = "smoke-ps-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())-$i"; $booked = HttpH POST "$GW/api/bookings" $bookBody $token @{ 'Idempotency-Key' = $key }; if (@(200, 201, 409) -contains $booked.Status) { break }; Start-Sleep 5 }
$bookingId = if ($booked.Status -eq 201) { (ConvertFrom-Json $booked.Body).id } else { $null }
if ($booked.Status -eq 201 -and (ConvertFrom-Json $booked.Body).status -eq 'PENDING_PAYMENT') { Pass "user books seat $seatId -> 201 PENDING_PAYMENT" } else { Bad 'book seat' "status $($booked.Status)" }
if ((SeatStatus $seatId) -eq 'HELD') { Pass 'seat map shows the seat HELD' } else { Bad 'seat not HELD' (SeatStatus $seatId) }
$replay = HttpH POST "$GW/api/bookings" $bookBody $token @{ 'Idempotency-Key' = $key }
if ($replay.Status -eq 200 -and (ConvertFrom-Json $replay.Body).id -eq $bookingId) { Pass 'replay with the same key -> 200, same booking' } else { Bad 'replay' "status $($replay.Status)" }

$email2 = "smoke2-ps-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())@example.com"
$b2 = "{`"email`":`"$email2`",`"password`":`"Smoke#12345`"}"
Http POST "$GW/api/users/register" $b2 | Out-Null
$token2 = (ConvertFrom-Json (Http POST "$GW/api/users/login" $b2).Body).accessToken
if ((HttpH POST "$GW/api/bookings" $bookBody $token2 @{ 'Idempotency-Key' = "other-$key" }).Status -eq 409) { Pass 'second user, same seat -> 409' } else { Bad 'second user was not rejected' }

$cancelled = HttpH POST "$GW/api/bookings/$bookingId/cancel" $null $token @{}
if ($cancelled.Status -eq 200 -and (ConvertFrom-Json $cancelled.Body).status -eq 'CANCELLED') { Pass 'cancel -> CANCELLED' } else { Bad 'cancel' "status $($cancelled.Status)" }
for ($i = 0; $i -lt 30; $i++) { if ((SeatStatus $seatId) -eq 'AVAILABLE') { break }; Start-Sleep 1 }
if ((SeatStatus $seatId) -eq 'AVAILABLE') { Pass 'seat released via outbox -> Kafka -> event-service' } else { Bad 'seat not released' (SeatStatus $seatId) }

$apps = Http GET 'http://localhost:8761/eureka/apps/BOOKING-SERVICE' -accept 'application/json'
$instances = ([regex]::Matches($apps.Body, '"status":"UP"')).Count   # UP only: replaced containers linger as DOWN until their lease expires
if ($instances -eq 2) { Pass 'Eureka lists 2 booking-service instances' } else { Bad 'booking-service instances' $instances }

# --- payment-service + fake provider (plan 4) ---
$seat2 = $seatList[1].id
$booked2 = HttpH POST "$GW/api/bookings" "{`"eventId`":$eventId,`"seatIds`":[$seat2]}" $token @{ 'Idempotency-Key' = "pay-$key" }
$booking2 = if ($booked2.Status -eq 201) { (ConvertFrom-Json $booked2.Body).id } else { $null }
if ($booking2) { Pass "user books seat $seat2 for payment" } else { Bad 'book seat for payment' "status $($booked2.Status)" }
$payBody = "{`"bookingId`":`"$booking2`"}"
for ($i = 0; $i -lt 24; $i++) { $payKey = "pay-$key-$i"; $paid = HttpH POST "$GW/api/payments" $payBody $token @{ 'Idempotency-Key' = $payKey }; if (@(200, 202, 402, 409) -contains $paid.Status) { break }; Start-Sleep 5 }
$paymentId = if ($paid.Status -eq 200) { (ConvertFrom-Json $paid.Body).id } else { $null }
if ($paid.Status -eq 200 -and (ConvertFrom-Json $paid.Body).status -eq 'SUCCEEDED') { Pass 'pay -> 200 SUCCEEDED' } else { Bad 'pay' "status $($paid.Status)" }
$repay = HttpH POST "$GW/api/payments" $payBody $token @{ 'Idempotency-Key' = $payKey }
if ($repay.Status -eq 200 -and (ConvertFrom-Json $repay.Body).id -eq $paymentId) { Pass 'pay replay with the same key -> 200, same payment' } else { Bad 'pay replay' "status $($repay.Status)" }
if ((HttpH POST "$GW/api/payments" $payBody $token @{ 'Idempotency-Key' = "pay-again-$key" }).Status -eq 409) { Pass 'second payment for the same booking -> 409' } else { Bad 'second payment was not rejected' }
$bStatus = $null
for ($i = 0; $i -lt 30; $i++) {
    $bStatus = (ConvertFrom-Json (HttpH GET "$GW/api/bookings/$booking2" $null $token @{}).Body).status
    if ($bStatus -eq 'CONFIRMED' -and (SeatStatus $seat2) -eq 'BOOKED') { break }; Start-Sleep 1
}
if ($bStatus -eq 'CONFIRMED') { Pass 'booking CONFIRMED (payment -> Kafka -> booking)' } else { Bad 'booking not confirmed' $bStatus }
if ((SeatStatus $seat2) -eq 'BOOKED') { Pass 'seat BOOKED (booking -> Kafka -> event-service)' } else { Bad 'seat not booked' (SeatStatus $seat2) }
$payApps = Http GET 'http://localhost:8761/eureka/apps/PAYMENT-SERVICE' -accept 'application/json'
if ($payApps.Body -match '"instanceId"') { Pass 'PAYMENT-SERVICE registered in Eureka' } else { Bad 'PAYMENT-SERVICE not in Eureka' }

# every service is running (and healthy when it has a healthcheck) — `ps -a` includes exited containers
$expected = & docker compose config --services
$states = @{}
& docker compose ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}' | ForEach-Object { $p = $_.Split('|'); $states[$p[0]] = @($states[$p[0]]) + ,$p }   # one entry per replica
$problems = foreach ($svc in $expected) {
    $replicas = @($states[$svc] | Where-Object { $_ })
    if (-not $replicas) { "[${svc}: no container]" }
    foreach ($s in $replicas) { if ($s[1] -ne 'running' -or ($s[2] -and $s[2] -ne 'healthy')) { "[${svc}: $($s -join ' ')]" } }
}
if (-not $problems) { Pass "all $(@($expected).Count) services running/healthy" } else { Bad 'services not running/healthy' ($problems -join ' ') }

if (-not $script:fail) { Write-Output 'SMOKE PASSED' } else { Write-Output 'SMOKE FAILED'; exit 1 }
