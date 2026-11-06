#!/usr/bin/env bash
# End-to-end smoke through the gateway against the running Compose stack (plans 1-2).
# Needs only curl, grep, sed, base64 (no jq).   Usage: bash infra/smoke/smoke.sh
set -u
cd "$(dirname "$0")/../.."
GW=http://localhost:8080
fail=0
pass() { echo "PASS  $1"; }
bad()  { echo "FAIL  $1"; [ -n "${2:-}" ] && echo "      $2"; fail=1; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
jfield() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }   # first string field from one-line JSON

# 1. gateway health
for i in $(seq 1 36); do curl -fs "$GW/actuator/health" | grep -q '"UP"' && break; sleep 5; done
curl -fs "$GW/actuator/health" | grep -q '"UP"' && pass "gateway health UP" || { bad "gateway health"; exit 1; }

# 2. user-service registered in Eureka
for i in $(seq 1 24); do [ "$(code -H 'Accept: application/json' http://localhost:8761/eureka/apps/USER-SERVICE)" = 200 ] && break; sleep 5; done
[ "$(code -H 'Accept: application/json' http://localhost:8761/eureka/apps/USER-SERVICE)" = 200 ] && pass "USER-SERVICE registered in Eureka" || bad "USER-SERVICE not in Eureka"

# 3. register (retry while the gateway's Eureka cache warms up)
EMAIL="smoke-$(date +%s)@example.com"; BODY="{\"email\":\"$EMAIL\",\"password\":\"Smoke#12345\"}"
for i in $(seq 1 24); do c=$(code -X POST "$GW/api/users/register" -H 'Content-Type: application/json' -d "$BODY"); [ "$c" = 201 ] && break; sleep 5; done
[ "$c" = 201 ] && pass "register → 201" || bad "register" "got $c"

# 4. login + token claims
LOGIN=$(curl -s -X POST "$GW/api/users/login" -H 'Content-Type: application/json' -d "$BODY")
TOKEN=$(echo "$LOGIN" | jfield accessToken)
if [ -n "$TOKEN" ]; then
  P=$(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#P} % 4 )) -ne 0 ]; do P="$P="; done
  CLAIMS=$(echo "$P" | base64 -d 2>/dev/null)
  echo "$CLAIMS" | grep -q '"iss":"ticketrush-user-service"' && echo "$CLAIMS" | grep -q 'ticketrush-api' \
    && echo "$CLAIMS" | grep -q '"token_use":"user"' && pass "login → token with iss/aud/token_use" || bad "token claims" "$CLAIMS"
else bad "login" "$LOGIN"; fi

# 5. /me with token
ME=$(curl -s "$GW/api/users/me" -H "Authorization: Bearer $TOKEN")
echo "$ME" | grep -q "\"email\":\"$EMAIL\"" && pass "/api/users/me returns the user" || bad "/me" "$ME"

# 6. /me without token
[ "$(code "$GW/api/users/me")" = 401 ] && pass "/me without token → 401" || bad "/me without token"

# 7. internal denied
[ "$(code "$GW/internal/anything" -H "Authorization: Bearer $TOKEN")" = 403 ] && pass "/internal/** → 403" || bad "/internal/**"

# 8. ADMIN-only event writes
[ "$(code -X POST "$GW/api/events" -H "Authorization: Bearer $TOKEN")" = 403 ] && pass "POST /api/events as USER → 403" || bad "event write as USER"

# 9. service ports not published
for p in 8081 8082 8085 8090; do
  if curl -s -m 3 "http://localhost:$p/actuator/health" >/dev/null 2>&1; then bad "port $p is reachable from the host"; else pass "port $p not published"; fi
done

# --- event-service (plan 2) ---
if [ -f .env ]; then ADMIN_EMAIL=$(sed -n 's/^ADMIN_EMAIL=//p' .env); ADMIN_PASSWORD=$(sed -n 's/^ADMIN_PASSWORD=//p' .env); fi
ADMIN_LOGIN=$(curl -s -X POST "$GW/api/users/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ADMIN_EMAIL:-admin@ticketrush.dev}\",\"password\":\"${ADMIN_PASSWORD:-Admin#12345}\"}")
ADMIN_TOKEN=$(echo "$ADMIN_LOGIN" | jfield accessToken)
[ -n "$ADMIN_TOKEN" ] && pass "admin login" || bad "admin login" "$ADMIN_LOGIN"

# sale opened 5 min ago: the Docker VM clock can lag the host by seconds, so "now" here may still be the future there
NOW=$(date -u -d "-5 minutes" +%Y-%m-%dT%H:%M:%SZ); START=$(date -u -d "+30 days" +%Y-%m-%dT%H:%M:%SZ)
EVENT_BODY="{\"name\":\"Smoke Show\",\"venue\":\"Arena\",\"startsAt\":\"$START\",\"saleOpensAt\":\"$NOW\",\"priceCents\":2500,\"rows\":2,\"seatsPerRow\":3}"
for i in $(seq 1 24); do
  CREATED=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/events" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d "$EVENT_BODY")
  [ "$(echo "$CREATED" | tail -1)" = 201 ] && break; sleep 5     # gateway may not have discovered event-service yet
done
EVENT_ID=$(echo "$CREATED" | head -1 | sed -n 's/^{"id":\([0-9]*\).*/\1/p')
[ "$(echo "$CREATED" | tail -1)" = 201 ] && [ -n "$EVENT_ID" ] && pass "admin creates event $EVENT_ID → 201" || bad "create event" "$CREATED"

DETAILS=$(curl -s "$GW/api/events/$EVENT_ID")
echo "$DETAILS" | grep -q '"availableSeats":6' && pass "event details show 6 available seats (anonymous read)" || bad "event details" "$DETAILS"
SEATS=$(curl -s "$GW/api/events/$EVENT_ID/seats")
[ "$(echo "$SEATS" | grep -o '"seatNo"' | wc -l)" -eq 6 ] && pass "seat map has 6 seats" || bad "seat map" "$SEATS"

# --- booking-service (plan 3) ---
SEAT_ID=$(echo "$SEATS" | sed -n 's/^\[{"id":\([0-9]*\).*/\1/p')
seat_status() { curl -s "$GW/api/events/$EVENT_ID/seats" | grep -o "{\"id\":$SEAT_ID,[^}]*}" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p'; }
BOOK_BODY="{\"eventId\":$EVENT_ID,\"seatIds\":[$SEAT_ID]}"
for i in $(seq 1 24); do
  KEY="smoke-$(date +%s)-$i"   # fresh key per attempt: a replay would only repeat a stored failure
  BOOKED=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/bookings" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BOOK_BODY")
  case "$(echo "$BOOKED" | tail -1)" in 201|200|409) break;; esac; sleep 5    # wait for discovery of booking-service
done
BOOKING_ID=$(echo "$BOOKED" | head -1 | jfield id)
[ "$(echo "$BOOKED" | tail -1)" = 201 ] && echo "$BOOKED" | grep -q '"status":"PENDING_PAYMENT"' && pass "user books seat $SEAT_ID → 201 PENDING_PAYMENT" || bad "book seat" "$BOOKED"
[ "$(seat_status)" = HELD ] && pass "seat map shows the seat HELD" || bad "seat not HELD" "$(seat_status)"
REPLAY=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/bookings" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BOOK_BODY")
[ "$(echo "$REPLAY" | tail -1)" = 200 ] && echo "$REPLAY" | grep -q "\"id\":\"$BOOKING_ID\"" && pass "replay with the same key → 200, same booking" || bad "replay" "$REPLAY"

EMAIL2="smoke2-$(date +%s)@example.com"
curl -s -o /dev/null -X POST "$GW/api/users/register" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL2\",\"password\":\"Smoke#12345\"}"
TOKEN2=$(curl -s -X POST "$GW/api/users/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL2\",\"password\":\"Smoke#12345\"}" | jfield accessToken)
[ "$(code -X POST "$GW/api/bookings" -H "Authorization: Bearer $TOKEN2" -H "Idempotency-Key: other-$KEY" -H 'Content-Type: application/json' -d "$BOOK_BODY")" = 409 ] \
  && pass "second user, same seat → 409" || bad "second user was not rejected"

CANCELLED=$(curl -s -X POST "$GW/api/bookings/$BOOKING_ID/cancel" -H "Authorization: Bearer $TOKEN")
echo "$CANCELLED" | grep -q '"status":"CANCELLED"' && pass "cancel → CANCELLED" || bad "cancel" "$CANCELLED"
for i in $(seq 1 30); do [ "$(seat_status)" = AVAILABLE ] && break; sleep 1; done
[ "$(seat_status)" = AVAILABLE ] && pass "seat released via outbox → Kafka → event-service" || bad "seat not released" "$(seat_status)"

INSTANCES=$(curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps/BOOKING-SERVICE | grep -o '"status":"UP"' | wc -l)   # UP only: replaced containers linger as DOWN until their lease expires
[ "$INSTANCES" -eq 2 ] && pass "Eureka lists 2 booking-service instances" || bad "booking-service instances" "$INSTANCES"

# --- payment-service + fake provider (plan 4) ---
SEAT2=$(echo "$SEATS" | grep -o '"id":[0-9]*' | sed -n 2p | cut -d: -f2)
seat2_status() { curl -s "$GW/api/events/$EVENT_ID/seats" | grep -o "{\"id\":$SEAT2,[^}]*}" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p'; }
BOOKED2=$(curl -s -X POST "$GW/api/bookings" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: pay-$KEY" -H 'Content-Type: application/json' -d "{\"eventId\":$EVENT_ID,\"seatIds\":[$SEAT2]}")
BOOKING2=$(echo "$BOOKED2" | jfield id)
[ -n "$BOOKING2" ] && pass "user books seat $SEAT2 for payment" || bad "book seat for payment" "$BOOKED2"
PAY_BODY="{\"bookingId\":\"$BOOKING2\"}"
for i in $(seq 1 24); do
  PAYKEY="pay-$KEY-$i"
  PAID=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/payments" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $PAYKEY" -H 'Content-Type: application/json' -d "$PAY_BODY")
  case "$(echo "$PAID" | tail -1)" in 200|202|402|409) break;; esac; sleep 5    # wait for discovery of payment-service
done
PAYMENT_ID=$(echo "$PAID" | head -1 | jfield id)
[ "$(echo "$PAID" | tail -1)" = 200 ] && echo "$PAID" | grep -q '"status":"SUCCEEDED"' && pass "pay → 200 SUCCEEDED" || bad "pay" "$PAID"
REPAY=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/payments" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $PAYKEY" -H 'Content-Type: application/json' -d "$PAY_BODY")
[ "$(echo "$REPAY" | tail -1)" = 200 ] && echo "$REPAY" | grep -q "\"id\":\"$PAYMENT_ID\"" && pass "pay replay with the same key → 200, same payment" || bad "pay replay" "$REPAY"
[ "$(code -X POST "$GW/api/payments" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: pay-again-$KEY" -H 'Content-Type: application/json' -d "$PAY_BODY")" = 409 ] \
  && pass "second payment for the same booking → 409" || bad "second payment was not rejected"
for i in $(seq 1 30); do
  BSTATUS=$(curl -s "$GW/api/bookings/$BOOKING2" -H "Authorization: Bearer $TOKEN" | jfield status)
  [ "$BSTATUS" = CONFIRMED ] && [ "$(seat2_status)" = BOOKED ] && break; sleep 1
done
[ "$BSTATUS" = CONFIRMED ] && pass "booking CONFIRMED (payment → Kafka → booking)" || bad "booking not confirmed" "$BSTATUS"
[ "$(seat2_status)" = BOOKED ] && pass "seat BOOKED (booking → Kafka → event-service)" || bad "seat not booked" "$(seat2_status)"
curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps/PAYMENT-SERVICE | grep -q '"instanceId"' \
  && pass "PAYMENT-SERVICE registered in Eureka" || bad "PAYMENT-SERVICE not in Eureka"

# 10. every service is running (and healthy when it has a healthcheck) — includes exited containers via -a
expected=$(docker compose config --services | sort)
states=$(docker compose ps -a --format '{{.Service}} {{.State}} {{.Health}} {{.ExitCode}}')
problems=""
for svc in $expected; do
  lines=$(echo "$states" | awk -v s="$svc" '$1 == s')   # one line per replica
  [ -z "$lines" ] && { problems="$problems [$svc: no container]"; continue; }
  while read -r _ state health _; do
    if [ "$state" != "running" ] || { [ -n "$health" ] && [ "$health" != "healthy" ] && [ "$health" != "0" ]; }; then
      problems="$problems [$svc: $state $health]"
    fi
  done <<< "$lines"
done
[ -z "$problems" ] && pass "all $(echo "$expected" | wc -w) services running/healthy" || bad "services not running/healthy" "$problems"

if [ $fail -eq 0 ]; then echo "SMOKE PASSED"; else echo "SMOKE FAILED"; exit 1; fi
