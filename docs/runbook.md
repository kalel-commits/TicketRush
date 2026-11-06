# TicketRush runbook

How to see and fix the things the system cannot fix by itself. Commands assume the Docker Compose stack
(`docker compose up -d`) and are run from the repository root.

## Dead-letter topics (spec §11.5)

A consumer retries a failing message 3 times (1 s, 2 s, 4 s). Then it, or any malformed message right away,
goes to `<topic>.DLT`. Each service logs every DLT message at ERROR (`Dead-lettered message on …`) and counts it
in `kafka.dlt.messages{topic}`.

| DLT topic | Consumer that gave up | Healed anyway by |
|---|---|---|
| `booking.confirmed.DLT` | event-service (seat → BOOKED) | seat reconciler (asks booking-service, books the seat) |
| `booking.cancelled.DLT` | event-service (seat → AVAILABLE) | seat reconciler (releases seats of ended bookings) |
| `payment.succeeded.DLT` | booking-service (booking → CONFIRMED) | payment reconciler (b): **refunds** once the booking has expired, so the customer loses the seats. Replay quickly (before the hold expires) to confirm instead |
| `payment.refund.requested.DLT` | payment-service (refund) | payment reconciler (b)/(c) |

A DLT message therefore delays recovery; it never makes an inconsistency permanent.

**List the messages on a DLT** (key, headers with the exception, value):

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic payment.succeeded.DLT --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.headers=true
```

**Replay one message** after fixing its cause. Copy its key and value and send them to the original topic.
Consumers deduplicate by the envelope `eventId`, so replaying an already-applied message is harmless.

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 \
  --topic payment.succeeded --property parse.key=true --property "key.separator=|" <<'EOF'
<bookingId>|<the original JSON envelope on one line>
EOF
```

## Reading metrics

Service ports are not published, so read metrics from inside the Compose network (actuator exposes `health` and
`metrics`; the gateway does not route `/actuator`):

```bash
docker compose exec event-service curl -s localhost:8082/actuator/metrics/reconciler.seats
docker compose exec payment-service curl -s "localhost:8085/actuator/metrics/reconciler.payments?tag=action:refunded"
docker compose exec booking-service curl -s localhost:8083/actuator/metrics/kafka.dlt.messages
docker compose exec payment-service curl -s "localhost:8085/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:provider&tag=state:open"
```

With 2 booking-service replicas, `exec` picks one; use `--index 2` for the other.

## Reconcilers (spec §10, §12)

| Metric | Meaning | Worry when |
|---|---|---|
| `reconciler.seats{action=booked}` | HELD seat whose booking is CONFIRMED was booked: a `booking.confirmed` was lost or late | it grows steadily (messages are being lost) |
| `reconciler.seats{action=released}` | HELD seat of an ended or unknown booking was released | same |
| `reconciler.payments{action=succeeded}` | ambiguous (PROCESSING) payment found charged at the provider | many: provider timeouts are common |
| `reconciler.payments{action=failed}` | PROCESSING payment declined, or not found twice ≥ 30 s apart | — |
| `reconciler.payments{action=verified}` | SUCCEEDED payment confirmed by its booking | — |
| `reconciler.payments{action=refund_requested}` / `refunded` | money taken for a booking that ended, or a refund retried | `refund_requested` without matching `refunded` |

**Money check:** `GET /internal/payments/reconciliation-report` (service token; payment-service only, not exposed
via the gateway) lists every provider charge whose local attempt disagrees (`mismatches`). An empty list means
every charge is accounted for. PROCESSING rows are reported separately (`localProcessing`) because the
reconciler resolves them within about a minute.

## Circuit breakers (spec §14)

`resilience4j.circuitbreaker.state` (tags `name`, `state`; 1 = current state) on each service's actuator metrics. Names: `eventHold`, `redis` (booking);
`bookingLookup`, `provider`, `bookingStatus` (payment); `bookingStatus` (event). While a breaker is open:

- `eventHold`: bookings fail fast with 503 and nothing is held.
- `redis`: the seat filter is skipped and MySQL decides (higher DB load).
- `provider`: payment attempts fail with 503 `DEPENDENCY_UNAVAILABLE`. The attempt is stored as FAILED with reason `PROVIDER_UNAVAILABLE`, and nothing is charged.
- `bookingLookup`: payments get 503.
- `bookingStatus`: the reconcilers skip their batch until the next cycle.

Breakers close by themselves after 10 s plus 3 healthy trial calls. Settings: `ticketrush.resilience.<name>.*`.
