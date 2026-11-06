# AI assistant end to end through the gateway (plan 7 Task 6). PowerShell twin of assistant-smoke.sh.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')
$GW = 'http://localhost:8080'
$script:fail = $false
function Pass($m) { Write-Output "PASS  $m" }
function Bad($m, $d) { Write-Output "FAIL  $m"; if ($d) { Write-Output "      $d" }; $script:fail = $true }
function EnvVal($k) { ((Get-Content .env | Where-Object { $_ -like "$k=*" } | Select-Object -First 1) -split '=', 2)[1] }
function Call($method, $url, $body, $token) {
    try {
        $h = @{}; if ($token) { $h.Authorization = "Bearer $token" }
        $p = @{ Method = $method; Uri = $url; Headers = $h; UseBasicParsing = $true; TimeoutSec = 30; ContentType = 'application/json' }
        if ($body) { $p.Body = $body }
        $r = Invoke-WebRequest @p
        return @{ Status = [int]$r.StatusCode; Body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object IO.StreamReader($resp.GetResponseStream())
            return @{ Status = [int]$resp.StatusCode; Body = $reader.ReadToEnd() }
        }
        return @{ Status = 0; Body = $_.Exception.Message }
    }
}

$email = "assistant-ps-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())@example.com"
$cred = "{`"email`":`"$email`",`"password`":`"Smoke#12345`"}"
for ($i = 0; $i -lt 24; $i++) { if ((Call POST "$GW/api/users/register" $cred).Status -eq 201) { break }; Start-Sleep 5 }
$token = (ConvertFrom-Json (Call POST "$GW/api/users/login" $cred).Body).accessToken
$userId = (ConvertFrom-Json (Call GET "$GW/api/users/me" $null $token).Body).id
function Ask($m) { Call POST "$GW/api/assistant/chat" "{`"message`":`"$m`"}" $token }

for ($i = 0; $i -lt 30; $i++) { $r = Ask 'What is the weather in Paris tomorrow?'; if ($r.Status -eq 200) { break }; Start-Sleep 5 }
if ($r.Status -eq 200 -and $r.Body -match '"grounded":false') { Pass 'off-topic question -> 200, not grounded, no model call' } else { Bad 'off-topic' $r.Body }

$key = EnvVal 'ANTHROPIC_API_KEY'
$r = Ask 'How many seats can I book?'
if ($key -and $key -ne 'not-configured') {
    if ($r.Status -eq 200 -and $r.Body -match 'booking-policy.md' -and $r.Body -match '4') { Pass 'policy question -> grounded answer from booking-policy.md' } else { Bad 'policy question (real Claude)' $r.Body }
} else {
    if ($r.Status -eq 503 -and $r.Body -match 'ASSISTANT_UNAVAILABLE' -and $r.Body -match 'at most 4 seats') { Pass 'policy question without an API key -> 503 with the booking-policy excerpt' } else { Bad 'policy question (no key)' $r.Body }
}

Ask 'Book seat A1 for event 1 for me' | Out-Null
$n = & docker compose exec -T -e "MYSQL_PWD=$(EnvVal 'MYSQL_ROOT_PASSWORD')" mysql mysql -uroot -N -B -e "SELECT COUNT(*) FROM booking_db.bookings WHERE user_id=$userId" 2>$null
if ("$n".Trim() -eq '0') { Pass "`"book it for me`" created no booking (user $userId)" } else { Bad 'assistant created a booking?' $n }

$codes = @(); for ($i = 0; $i -lt 11; $i++) { $codes += (Ask 'Was I charged twice?').Status }
if ($codes -contains 429) { Pass 'assistant rate limit -> 429 within 11 requests' } else { Bad 'no 429 in 11 requests' ($codes -join ' ') }

if ((Call POST "$GW/api/assistant/chat" '{"message":"hi"}').Status -eq 401) { Pass 'no token -> 401' } else { Bad 'anonymous chat not rejected' }

$admin = (ConvertFrom-Json (Call POST "$GW/api/users/login" "{`"email`":`"$(EnvVal 'ADMIN_EMAIL')`",`"password`":`"$(EnvVal 'ADMIN_PASSWORD')`"}").Body).accessToken
$r = Call POST "$GW/api/assistant/admin/reindex" $null $admin
if ($r.Status -eq 202 -and $r.Body -match '"documents":5') { Pass 'admin reindex -> 202 (5 documents)' } else { Bad 'reindex' $r.Body }
if ((Call POST "$GW/api/assistant/admin/reindex" $null $token).Status -eq 403) { Pass 'reindex as USER -> 403' } else { Bad 'reindex not admin-only' }

if (-not $script:fail) { Write-Output 'ASSISTANT SMOKE PASSED' } else { Write-Output 'ASSISTANT SMOKE FAILED'; exit 1 }
