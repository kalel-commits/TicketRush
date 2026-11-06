#!/usr/bin/env bash
# Runs one load scenario against the running Compose stack and checks its pass criteria (spec §16.2-16.4).
#   load/run.sh s1|s2|s3|s4|s5
set -u
cd "$(dirname "$0")/.."
SCENARIO=${1:?usage: load/run.sh s1|s2|s3|s4|s5}
ROOT=$(pwd -W 2>/dev/null || pwd)            # Windows path for the docker volume under Git Bash
env_val() { grep "^$1=" .env | head -1 | cut -d= -f2-; }
ROOT_PW=$(env_val MYSQL_ROOT_PASSWORD)
sql() { docker compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql mysql -uroot -N -B -e "$1"; }
fail=0
check() { if [ "$1" = ok ]; then echo "PASS  $2"; else echo "FAIL  $2"; fail=1; fi; }

k6() {   # k6 <script> [ENV=VAL ...]
  local script=$1; shift
  local envs=(-e "ADMIN_EMAIL=$(env_val ADMIN_EMAIL)" -e "ADMIN_PASSWORD=$(env_val ADMIN_PASSWORD)")
  for kv in "$@"; do envs+=(-e "$kv"); done
  MSYS_NO_PATHCONV=1 docker run --rm --network ticketrush_default -v "$ROOT/load:/scripts" "${envs[@]}" \
    grafana/k6:0.54.0 run --quiet "/scripts/$script"
}

# Background sampler during crowd runs: max Hikari pending (booking replica 1) and max outbox lag (ms).
start_sampler() {
  : > load/results/sampler.txt
  ( while :; do
      p=$(docker compose exec -T booking-service curl -s localhost:8083/actuator/metrics/hikaricp.connections.pending 2>/dev/null |
          sed -n 's/.*"value":\([0-9.]*\).*/\1/p')
      l=$(sql "SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), NOW(3)) DIV 1000, 0) FROM booking_db.outbox_events WHERE published_at IS NULL" 2>/dev/null)
      echo "${p:-0} ${l:-0}" >> load/results/sampler.txt
      sleep 2
    done ) &
  SAMPLER=$!
}
stop_sampler() {
  kill "$SAMPLER" 2>/dev/null; wait "$SAMPLER" 2>/dev/null
  awk 'BEGIN{p=0;l=0} {if($1>p)p=$1; if($2>l)l=$2} END{printf "hikari pending max %s, outbox lag max %s ms\n", p, l}' load/results/sampler.txt
}

metric() {   # metric <json> <python expression over m = metrics dict>
  python -c "import json,sys; m=json.load(open(sys.argv[1]))['metrics']; print($2)" "$1" 2>/dev/null
}

crowd_report() {   # crowd_report <name>  -> prints the numbers the README needs
  local j=load/results/$1-latest.json
  echo "--- $1 numbers ---"
  metric "$j" "'http_reqs %.0f, %.1f req/s' % (m['http_reqs']['values']['count'], m['http_reqs']['values']['rate'])"
  metric "$j" "'POST /api/bookings p50 %.1f / p95 %.1f / p99 %.1f ms' % tuple(m['http_req_duration{name:book}']['values'][k] for k in ('med','p(95)','p(99)'))"
  metric "$j" "'book responses: ' + ', '.join('%s=%d' % (k[5:], v['values']['count']) for k,v in sorted(m.items()) if k.startswith('book_') and v['values'].get('count'))"
  metric "$j" "'pay responses: ' + ', '.join('%s=%d' % (k[4:], v['values']['count']) for k,v in sorted(m.items()) if k.startswith('pay_') and k != 'pay_to_confirmed' and v['values'].get('count'))"
  metric "$j" "'confirmed %d, sold out after %.1f s, pay->confirmed p95 %.0f ms' % (m['bookings_confirmed']['values']['count'], m['confirmed_after_open']['values']['max']/1000, m['pay_to_confirmed']['values']['p(95)'])"
  metric "$j" "'5xx share of bookings %.3f %%' % (100*m['http_req_failed{name:book}']['values']['rate'])"
}

quiet_and_invariants() {   # quiet_and_invariants <event> <max wait s>
  local ev=$1 wait=$2
  for i in $(seq 1 $((wait / 5))); do
    n=$(sql "SELECT (SELECT COUNT(*) FROM booking_db.outbox_events WHERE published_at IS NULL)
             + (SELECT COUNT(*) FROM payment_db.outbox_events WHERE published_at IS NULL)
             + (SELECT COUNT(*) FROM payment_db.payments WHERE status='PROCESSING')
             + (SELECT COUNT(*) FROM booking_db.bookings WHERE event_id=$ev AND status IN ('HOLDING'))")
    [ "$n" = 0 ] && break; sleep 5
  done
  bash load/invariants.sh --event "$ev" --quiet || fail=1
}

event_id() {   # the event k6 created; abort the scenario if k6 never got that far (setup failed)
  local ev; ev=$(grep -o '"id":[0-9]*' "load/results/$1-event.json" 2>/dev/null | head -1 | cut -d: -f2)
  if [ -z "$ev" ]; then echo "FAIL  k6 created no event (setup failed, see the k6 output above)" >&2; echo "$1 FAILED" >&2; exit 1; fi
  echo "$ev"
}

set_env_and_recreate() {   # set_env_and_recreate SERVICE VAR=VAL ...
  local svc=$1; shift
  env "$@" docker compose up -d --wait "$svc" >/dev/null 2>&1
}

case "$SCENARIO" in
  s1|s5)
    bash load/seed-users.sh >/dev/null || exit 1
    start_sampler
    if [ "$SCENARIO" = s5 ]; then bash load/chaos.sh & CHAOS=$!; fi
    k6 s1-crowd.js NAME=$SCENARIO
    [ "$SCENARIO" = s5 ] && wait "$CHAOS"
    stop_sampler
    EV=$(event_id $SCENARIO) || exit 1
    if [ "$SCENARIO" = s5 ]; then
      docker compose up -d --wait >/dev/null 2>&1   # everything back (chaos.sh restarts too; this is the safety net)
      quiet_and_invariants "$EV" 240
    else
      quiet_and_invariants "$EV" 60
    fi
    c=$(sql "SELECT COUNT(*) FROM booking_db.bookings WHERE event_id=$EV AND status='CONFIRMED'")
    if [ "$SCENARIO" = s1 ]; then check "$([ "$c" = 100 ] && echo ok)" "exactly 100 CONFIRMED (got $c)"
    else check "$([ "$c" -le 100 ] && echo ok)" "never more than 100 CONFIRMED under chaos (got $c)"; fi
    rejected_in_db=$(sql "SELECT COUNT(*) FROM booking_db.bookings WHERE event_id=$EV AND status='FAILED' AND failure_reason='SEATS_UNAVAILABLE'")
    crowd_report "$SCENARIO"
    total409=$(metric load/results/$SCENARIO-latest.json "m.get('book_409',{'values':{'count':0}})['values']['count']")
    echo "Redis filter: $total409 x 409, $rejected_in_db reached MySQL -> $(python -c "t=float('${total409:-0}'); print('%.1f %% filtered' % (100*(1-${rejected_in_db:-0}/t)) if t else 'n/a')")"
    ;;
  s2)
    bash load/seed-users.sh >/dev/null || exit 1
    k6 s2-abuser.js
    grep -q "S2 PASSED" load/results/s2-verdict.txt; check "$([ $? = 0 ] && echo ok)" "S2 per-user 429 counts within +-10 %"
    ;;
  s3)
    bash load/seed-users.sh >/dev/null || exit 1
    set_env_and_recreate fake-payment-provider PROVIDER_DECLINE_RATE=0.3
    set_env_and_recreate booking-service BOOKING_HOLD_SECONDS=60
    k6 s1-crowd.js NAME=s3 VUS=300 MAX_PAY_TRIES=3
    EV=$(event_id s3) || exit 1
    sleep 70   # let unpaid 60 s holds expire and the sweeper/reconcilers settle
    quiet_and_invariants "$EV" 120
    crowd_report s3
    set_env_and_recreate fake-payment-provider PROVIDER_DECLINE_RATE=0.0
    set_env_and_recreate booking-service BOOKING_HOLD_SECONDS=600
    ;;
  s4)
    bash load/seed-users.sh >/dev/null || exit 1
    set_env_and_recreate fake-payment-provider PROVIDER_TIMEOUT_AFTER_CHARGE_RATE=0.2
    k6 s1-crowd.js NAME=s4 VUS=200
    EV=$(event_id s4) || exit 1
    quiet_and_invariants "$EV" 180   # waits for zero PROCESSING (reconciler: lookups 15 s + 30 s apart)
    p=$(sql "SELECT COUNT(*) FROM payment_db.payments WHERE status='PROCESSING'")
    check "$([ "$p" = 0 ] && echo ok)" "zero PROCESSING after reconciliation (got $p)"
    d=$(sql "SELECT COUNT(*) FROM (SELECT booking_id FROM payment_db.payments WHERE status IN ('SUCCEEDED','REFUND_PENDING','REFUNDED') GROUP BY booking_id HAVING COUNT(*) > 1) x")
    check "$([ "$d" = 0 ] && echo ok)" "no booking charged twice (got $d)"
    crowd_report s4
    set_env_and_recreate fake-payment-provider PROVIDER_TIMEOUT_AFTER_CHARGE_RATE=0.0
    ;;
  *) echo "unknown scenario $SCENARIO"; exit 2;;
esac
[ $fail = 0 ] && echo "$SCENARIO PASSED" || echo "$SCENARIO FAILED"
exit $fail
