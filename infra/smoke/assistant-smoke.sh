#!/usr/bin/env bash
# AI assistant end to end through the gateway (plan 7 Task 6). Needs: discovery-server, mysql, redis, user-service,
# api-gateway, qdrant, ai-assistant. Works with or without ANTHROPIC_API_KEY: without one, model-backed answers must
# degrade to 503 ASSISTANT_UNAVAILABLE with the retrieved policy excerpts.
set -u
cd "$(dirname "$0")/../.."
GW=http://localhost:8080
fail=0
pass() { echo "PASS  $1"; }
bad() { echo "FAIL  $1"; [ -n "${2:-}" ] && echo "      ${2:0:300}"; fail=1; }
jf() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p"; }
ROOT_PW=$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2-)
KEY=$(grep '^ANTHROPIC_API_KEY=' .env | cut -d= -f2-)

EMAIL="assistant-$(date +%s)@example.com"; BODY="{\"email\":\"$EMAIL\",\"password\":\"Smoke#12345\"}"
for i in $(seq 1 24); do [ "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/api/users/register -H 'Content-Type: application/json' -d "$BODY")" = 201 ] && break; sleep 5; done
TOKEN=$(curl -s -X POST $GW/api/users/login -H 'Content-Type: application/json' -d "$BODY" | jf accessToken)
USER_ID=$(curl -s $GW/api/users/me -H "Authorization: Bearer $TOKEN" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
ask() { curl -s -w '\n%{http_code}' -X POST $GW/api/assistant/chat -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"message\":\"$1\"}"; }

for i in $(seq 1 30); do R=$(ask "What is the weather in Paris tomorrow?"); [ "$(echo "$R" | tail -1)" = 200 ] && break; sleep 5; done   # wait for discovery + model download
[ "$(echo "$R" | tail -1)" = 200 ] && echo "$R" | grep -q '"grounded":false' && pass "off-topic question -> 200, not grounded, no model call" || bad "off-topic" "$R"

R=$(ask "How many seats can I book?")
if [ -n "$KEY" ] && [ "$KEY" != "not-configured" ]; then
  [ "$(echo "$R" | tail -1)" = 200 ] && echo "$R" | grep -q '"source":"booking-policy.md"' && echo "$R" | grep -q '4' \
    && pass "policy question -> grounded answer from booking-policy.md" || bad "policy question (real Claude)" "$R"
else
  [ "$(echo "$R" | tail -1)" = 503 ] && echo "$R" | grep -q 'ASSISTANT_UNAVAILABLE' && echo "$R" | grep -q 'at most 4 seats' \
    && pass "policy question without an API key -> 503 with the booking-policy excerpt" || bad "policy question (no key)" "$R"
fi

ask "Book seat A1 for event 1 for me" > /dev/null
n=$(docker compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql mysql -uroot -N -B -e "SELECT COUNT(*) FROM booking_db.bookings WHERE user_id=${USER_ID:-0}" 2>/dev/null)
[ "$n" = 0 ] && pass "\"book it for me\" created no booking (user $USER_ID)" || bad "assistant created a booking?" "$n"

codes=""; for i in $(seq 1 11); do codes="$codes $(ask "Was I charged twice?" | tail -1)"; done
echo "$codes" | grep -q 429 && pass "assistant rate limit -> 429 within 11 requests" || bad "no 429 in 11 requests" "$codes"

[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/api/assistant/chat -H 'Content-Type: application/json' -d '{"message":"hi"}')" = 401 ] \
  && pass "no token -> 401" || bad "anonymous chat not rejected"

AE=$(grep '^ADMIN_EMAIL=' .env | cut -d= -f2-); AP=$(grep '^ADMIN_PASSWORD=' .env | cut -d= -f2-)
ADMIN=$(curl -s -X POST $GW/api/users/login -H 'Content-Type: application/json' -d "{\"email\":\"$AE\",\"password\":\"$AP\"}" | jf accessToken)
R=$(curl -s -w '\n%{http_code}' -X POST $GW/api/assistant/admin/reindex -H "Authorization: Bearer $ADMIN")
[ "$(echo "$R" | tail -1)" = 202 ] && echo "$R" | grep -q '"documents":5' && pass "admin reindex -> 202 (5 documents)" || bad "reindex" "$R"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/api/assistant/admin/reindex -H "Authorization: Bearer $TOKEN")" = 403 ] \
  && pass "reindex as USER -> 403" || bad "reindex not admin-only"

if [ $fail -eq 0 ]; then echo "ASSISTANT SMOKE PASSED"; else echo "ASSISTANT SMOKE FAILED"; exit 1; fi
