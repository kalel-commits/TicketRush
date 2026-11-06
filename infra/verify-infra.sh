#!/usr/bin/env bash
# Proves per-service DB isolation (permissions, not missing tables), MySQL version/time zone, Redis and Kafka.
# Usage: bash infra/verify-infra.sh   (run from anywhere; needs `docker compose up -d mysql redis kafka`)
set -u
cd "$(dirname "$0")/.."
if [ -f .env ]; then set -a; . ./.env; set +a; else set -a; . ./.env.example; set +a; fi

fail=0
pass() { echo "PASS  $1"; }
bad()  { echo "FAIL  $1"; [ -n "${2:-}" ] && echo "      $2"; fail=1; }

# sql USER PASSWORD "STATEMENTS" -> prints output (stdout+stderr), returns mysql exit code
sql() {
  local user=$1 pw=$2; shift 2
  docker compose exec -T -e MYSQL_PWD="$pw" mysql mysql -u"$user" -N -B -e "$*" 2>&1
}

declare -A PW=( [user_svc]="$USER_DB_PASSWORD" [event_svc]="$EVENT_DB_PASSWORD"
                [booking_svc]="$BOOKING_DB_PASSWORD" [payment_svc]="$PAYMENT_DB_PASSWORD" )
declare -A OWN=( [user_svc]=user_db [event_svc]=event_db [booking_svc]=booking_db [payment_svc]=payment_db )
declare -A NEIGHBOUR=( [user_svc]=event_db [event_svc]=booking_db [booking_svc]=payment_db [payment_svc]=user_db )
SCHEMAS="user_db event_db booking_db payment_db"

# 1. Fixture as root in every schema
fixture=""
for s in $SCHEMAS; do fixture="$fixture DROP TABLE IF EXISTS $s.isolation_probe; CREATE TABLE $s.isolation_probe(id INT PRIMARY KEY); INSERT INTO $s.isolation_probe VALUES (1);"; done
if out=$(sql root "$MYSQL_ROOT_PASSWORD" "$fixture"); then pass "root created isolation_probe fixtures"; else bad "root fixture" "$out"; fi

# 2. Root can read the fixture (so a later failure is about permissions, not a missing table)
out=$(sql root "$MYSQL_ROOT_PASSWORD" "SELECT COUNT(*) FROM event_db.isolation_probe")
[ "$out" = "1" ] && pass "root reads event_db.isolation_probe" || bad "root read fixture" "$out"

for u in user_svc event_svc booking_svc payment_svc; do
  # 3/7. Neighbour schema must be denied with ERROR 1142 specifically
  out=$(sql "$u" "${PW[$u]}" "SELECT COUNT(*) FROM ${NEIGHBOUR[$u]}.isolation_probe")
  if echo "$out" | grep -q "ERROR 1142"; then pass "$u denied on ${NEIGHBOUR[$u]} (ERROR 1142)"; else bad "$u on ${NEIGHBOUR[$u]} not denied with 1142" "$out"; fi
  # 5/7. Own schema: create + drop works
  if out=$(sql "$u" "${PW[$u]}" "CREATE TABLE ${OWN[$u]}.rw_probe(id INT); DROP TABLE ${OWN[$u]}.rw_probe;"); then pass "$u can create/drop in ${OWN[$u]}"; else bad "$u DDL in own schema" "$out"; fi
done

# 4. user_svc sees only its own schema
out=$(sql user_svc "${PW[user_svc]}" "SHOW DATABASES")
if echo "$out" | grep -qx "user_db" && ! echo "$out" | grep -qx "event_db"; then pass "user_svc sees user_db, not event_db"; else bad "user_svc schema visibility" "$out"; fi

# 6. user_svc cannot create schemas
out=$(sql user_svc "${PW[user_svc]}" "CREATE DATABASE rogue_db")
echo "$out" | grep -q "ERROR 1044" && pass "user_svc cannot CREATE DATABASE (ERROR 1044)" || bad "user_svc CREATE DATABASE not denied with 1044" "$out"

# 8. Cleanup
cleanup=""; for s in $SCHEMAS; do cleanup="$cleanup DROP TABLE IF EXISTS $s.isolation_probe;"; done
sql root "$MYSQL_ROOT_PASSWORD" "$cleanup" >/dev/null && pass "fixtures dropped" || bad "cleanup"

# 9. Version + time zone
out=$(sql root "$MYSQL_ROOT_PASSWORD" "SELECT @@version, @@global.time_zone")
if echo "$out" | grep -qE "^8\.0\.[0-9]+.*\+00:00$"; then pass "MySQL 8.0, UTC ($out)"; else bad "MySQL version/time zone" "$out"; fi

# 10. Redis
out=$(docker compose exec -T redis redis-cli PING 2>&1)
[ "$out" = "PONG" ] && pass "redis PING" || bad "redis" "$out"

# 11. Kafka
# MSYS_NO_PATHCONV stops Git Bash on Windows from rewriting /opt/... into a Windows path (no effect elsewhere)
if out=$(MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list 2>&1); then pass "kafka topics list"; else bad "kafka" "$out"; fi

if [ $fail -eq 0 ]; then echo "ALL INFRA CHECKS PASSED"; else echo "INFRA CHECKS FAILED"; exit 1; fi
