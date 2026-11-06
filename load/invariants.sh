#!/usr/bin/env bash
# Spec §16.4 invariants + the payment reconciliation report. Exit 0 only if every check holds.
#   load/invariants.sh [--event <id>] [--quiet]     (--quiet adds check 5: nothing in flight / stuck)
set -u
cd "$(dirname "$0")/.."
EVENT=NULL; QUIET=0
while [ $# -gt 0 ]; do
  case "$1" in
    --event) EVENT="$2"; shift 2;;
    --quiet) QUIET=1; shift;;
    *) echo "usage: $0 [--event <id>] [--quiet]"; exit 2;;
  esac
done
env_val() { grep "^$1=" .env | head -1 | cut -d= -f2-; }
ROOT_PW=$(env_val MYSQL_ROOT_PASSWORD); SECRET=$(env_val INTERNAL_JWT_SECRET)

fail=0
errfile=$(mktemp)
violations=$( { echo "SET @event = $EVENT, @quiet = $QUIET;"; cat load/invariants.sql; } |
  docker compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql mysql -uroot -N -B 2>"$errfile")
status=$?
if [ $status -ne 0 ] || [ -s "$errfile" ]; then   # a broken query must never read as "no violations"
  echo "FAIL  invariants could not run (mysql exit $status): $(head -3 "$errfile")"
  fail=1
elif [ -n "$violations" ]; then
  echo "FAIL  invariants (first column = check number):"; echo "$violations" | head -20 | sed 's/^/      /'
  fail=1
else
  echo "PASS  invariants 1-4$([ $QUIET = 1 ] && echo ' + 5 (quiet)') ${EVENT/NULL/all events}"
fi

# Service token (HS256, same claims as common ServiceTokens) for /internal/payments/reconciliation-report.
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
header=$(printf '{"alg":"HS256"}' | b64url)
payload=$(printf '{"iss":"ticketrush-internal","aud":["ticketrush-internal"],"sub":"load-tooling","role":"SERVICE","token_use":"service","iat":%d,"exp":%d}' "$now" $((now + 300)) | b64url)
sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)
report=$(docker compose exec -T payment-service curl -s -H "Authorization: Bearer $header.$payload.$sig" \
  localhost:8085/internal/payments/reconciliation-report)
if echo "$report" | grep -q '"mismatches":\[\]'; then
  echo "PASS  reconciliation report: 0 mismatches $(echo "$report" | grep -o '"providerCharges":[0-9]*,"providerSucceeded":[0-9]*,"providerRefunded":[0-9]*')"
else
  echo "FAIL  reconciliation report: ${report:0:400}"; fail=1
fi
rm -f "$errfile"
exit $fail
