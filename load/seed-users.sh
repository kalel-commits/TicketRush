#!/usr/bin/env bash
# Seeds load-1..load-N@example.com (password Load#12345) for the k6 scenarios (spec §16.2). Idempotent.
# One template user is registered through the gateway (real BCrypt hash); the rest copy its hash via SQL.
set -eu
cd "$(dirname "$0")/.."
N=${1:-1000}
ROOT_PW=$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2-)
sql() { docker compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql mysql -uroot -N -B "$@"; }

# The template must exist with a real hash before anything is copied: right after `up`, the gateway may not have
# discovered user-service yet, so retry (a silent failure here once seeded 1,000 users with an empty hash).
for i in $(seq 1 24); do
  curl -s -o /dev/null -X POST http://localhost:8080/api/users/register -H 'Content-Type: application/json' \
    -d '{"email":"load-template@example.com","password":"Load#12345"}' || true
  HASH=$(echo "SELECT password_hash FROM user_db.users WHERE email = 'load-template@example.com'" | sql)
  case "$HASH" in \$2*) break;; esac
  sleep 5
done
case "$HASH" in \$2*) ;; *) echo "FAIL  template user could not be registered through the gateway"; exit 1;; esac

# Upsert: also repairs rows from an earlier broken run (the hash is refreshed, never left empty).
sql <<SQL
SET SESSION cte_max_recursion_depth = $((N + 10));
INSERT INTO user_db.users (email, password_hash, role, created_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $N)
SELECT CONCAT('load-', n, '@example.com'), '$HASH', 'USER', NOW(3) FROM seq
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash);
SELECT CONCAT('load users present: ', COUNT(*), ', with a valid hash: ', SUM(password_hash LIKE '\$2%'))
  FROM user_db.users WHERE email LIKE 'load-%@example.com' AND email <> 'load-template@example.com';
SQL
