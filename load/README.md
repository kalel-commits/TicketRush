# Load tests (spec §16.2–16.4)

Test tooling only. k6 runs in Docker (`grafana/k6:0.54.0`) on the Compose network, so nothing needs to be installed
except Docker and Git Bash (Windows) or bash.

```bash
docker compose up -d --build --wait     # the full stack
bash load/run.sh s1    # Crowd: 1,000 users, 100 seats       -> exactly 100 CONFIRMED + invariants
bash load/run.sh s2    # Abuser: 10 users x ~20 req/s, 30 s  -> 429s per user within +-10 % of the limit
bash load/run.sh s3    # Declines: 300 users, 30 % declines, 60 s holds (recreates provider + booking, then restores)
bash load/run.sh s4    # Ambiguous: 200 users, 20 % "charged but answered too late" -> 0 PROCESSING, 0 mismatches
bash load/run.sh s5    # Chaos: S1 while event-service, Kafka and one booking replica go down -> invariants after recovery
bash load/invariants.sh [--event <id>] [--quiet]   # the §16.4 checks + payment reconciliation report, any time
```

- `seed-users.sh` creates `load-1..1000@example.com` (password `Load#12345`) on first use. It's idempotent and
  repairs bad rows.
- Each run creates its own event whose sale opens 30 s after k6's setup, and every virtual user waits for that
  instant.
- Results: `load/results/<scenario>-latest.json` (k6 summary, no tokens) plus a summary on stdout. The measured
  numbers are in the root README.
- `invariants.sql` reads all four schemas as MySQL root. That is acceptable for test tooling only; services never
  cross schemas.
