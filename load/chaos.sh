#!/usr/bin/env bash
# S5 disruptions during an S1 crowd (spec §16.2), relative to the sale's start barrier:
#   +5 s  stop event-service for 20 s   |   +30 s stop kafka for 30 s   |   +70 s kill one booking-service replica
# The barrier is read from the event k6 creates (newest "Load …" event created after this script started).
set -u
cd "$(dirname "$0")/.."
ROOT_PW=$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2-)
sql() { docker compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql mysql -uroot -N -B -e "$1" 2>/dev/null; }
log() { echo "[chaos $(date +%T)] $*"; }

started=$(sql "SELECT NOW(3)")
for i in $(seq 1 180); do   # k6 setup logs 1,000 users in first (~70 s)
  left=$(sql "SELECT TIMESTAMPDIFF(MICROSECOND, NOW(3), sale_opens_at) DIV 1000 FROM event_db.events
              WHERE name LIKE 'Load %' AND created_at >= '$started' ORDER BY id DESC LIMIT 1")
  [ -n "$left" ] && break; sleep 2
done
[ -z "$left" ] && { log "no load event appeared; giving up"; exit 1; }
at() { local target_ms=$(( left + $1 * 1000 )); local now_ms=$(( ($(date +%s%N) - t0) / 1000000 )); local wait=$(( target_ms - now_ms )); [ $wait -gt 0 ] && sleep "$(awk "BEGIN{print $wait/1000}")"; }
t0=$(date +%s%N)
log "sale opens in ${left} ms"

at 5;  log "stop event-service";  docker compose stop event-service >/dev/null 2>&1
at 25; log "start event-service"; docker compose start event-service >/dev/null 2>&1
at 30; log "stop kafka";          docker compose stop kafka >/dev/null 2>&1
at 60; log "start kafka";         docker compose start kafka >/dev/null 2>&1
at 70; log "kill booking-service replica 2"; docker compose kill --index 2 booking-service >/dev/null 2>&1 \
         || docker kill ticketrush-booking-service-2 >/dev/null 2>&1
at 85; log "restore booking-service replicas"; docker compose up -d --wait booking-service >/dev/null 2>&1
log "done"
