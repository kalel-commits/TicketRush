# TicketRush live demo: presentation walkthrough

About 10 minutes. Everything on screen comes from the real running system: real accounts, real HTTP calls through the
gateway, and real Redis, MySQL and Kafka state. Nothing is animated from a script.

## Before the interview (5 minutes)

```bash
docker compose up -d --wait          # the whole stack, 13 containers healthy
cd frontend && npm run dev           # http://localhost:5173 (the demo harness only exists on the dev server)
```

1. Open **http://localhost:5173/demo/flash-sale** (nav: **Live demo**). You don't need to sign in: the page uses its
   own demo buyer accounts.
2. Click **Start a new race** once as a rehearsal. The first click after a restart also warms the services up.
3. Under **Payment scenarios**, make sure **Succeeds** is selected.
4. Optional, to show a hold expiring live: restart booking-service with a short hold:
   `BOOKING_HOLD_SECONDS=90 docker compose up -d booking-service`. Restart it without the variable afterwards. The
   default hold is 10 minutes.

## 1. The problem (30 s)

**Say:** "TicketRush is the backend for a flash sale: thousands of people hit the same seats in the same second. The
hard part isn't the booking form, it's making sure a seat can never be sold twice, and a card never charged twice,
when everyone presses the button at once and parts of the system fail."

## 2. Two buyers, one seat (2 min)

**Click:** **Start a new race**.

**Point at** the two panels. **Say:** "These are two separate user accounts with separate login tokens. Each panel
polls the real seat map through the gateway. Both see A1 as available."

**Click:** **Race both buyers for A1**.

**Point at** one panel's green **"You got seat A1"** and the other's red **"Seat A1 is no longer available, 409"**.
**Say:** "Both requests left in the same instant. Exactly one got the seat; the other got a conflict and no booking was
created. The loser's map now shows A1 as held, and it has no Pay button."

## 3. Why: proven from the stores (2 min)

**Scroll to** **Behind the scenes**. **Say:** "The client can't tell why it lost: Redis and MySQL rejections both return
the same 409. So each step here is proven from the data itself, not assumed."

- **Step 2, Redis lock.** "booking-service runs an atomic Lua script on Redis: it checks every seat's lock key and
  sets all of them or none. The value is the owner, user id plus idempotency key. The observer reads that key: the
  winner owns it. The loser was rejected here, so it never even wrote a booking row."
- **Step 3, MySQL.** "Redis is only a fast filter. The source of truth is a conditional `UPDATE … WHERE
  status='AVAILABLE'` in event-service. Its row now points at the winner's booking."
- **Point at the System observer:** the Redis key with its TTL, the seat counts from `event_db`, and the booking rows.

**If the observer's "Redis filter bypassed" count is above 0, say:** "That's real too. If Redis is slow (over a
200 ms timeout) the filter deliberately fails open, and MySQL still guarantees one winner. You can see the loser was
refused by MySQL, with a FAILED booking row." *(This happens on a cold start and under bursts on a laptop.)*

## 4. Pay, and the event flow (1.5 min)

**Click:** **Pay $25.00** on the winning panel.

**Point at:** payment SUCCEEDED → booking CONFIRMED → step 7 "seat BOOKED".

**Say:** "payment-service charged the provider and, in the **same database transaction**, wrote a `payment.succeeded`
event to an outbox table. A relay publishes it to Kafka. booking-service consumes it and confirms; its
`booking.confirmed` event makes event-service mark the seat BOOKED. If any service crashes in between, nothing is lost
and nothing is applied twice: consumers dedupe through `processed_events`. The observer shows outbox lag and consumer
lag."

## 5. When payments go wrong (2 min)

**Click:** **Start a new race**. In Buyer A, pick **A2** and click **Book A2**.

1. Select **Declined**, then click **Pay**. Shows **402 DECLINED**, payment FAILED, booking still held. **Say:** "No
   money taken, the seat stays held, and a retry is a new attempt."
2. Select **Times out after charging**, then click **Pay** again. Shows **PROCESSING**. **Say:** "This is the nasty
   case: the provider took the money but answered after we gave up. We don't guess. The payment stays PROCESSING and a
   reconciler asks the provider every 30 seconds." Wait up to about 45 s: **SUCCEEDED → CONFIRMED**, and the observer
   shows the reconciler's actions. **Say:** "If the hold had already expired, the same path refunds instead."
3. Select **Succeeds** again.

## 6. The flash crowd (2 min)

**Scroll to** **Flash-sale load test**. Leave **100 buyers / 20 seats** and click **Run load test**.

**Say while it runs:** "This runs server-side: 100 separate accounts, each with its own token, fire at the same moment,
5 buyers per seat. It does not use one browser session."

**Point at:**
- **Seat holds won: 20 (at most 20)**
- **Seats double-booked (SQL): 0**
- **Duplicate bookings prevented: 80**
- latency p50/p95/p99 and total time
- **Invariants: all pass**

**Say:** "Correctness isn't inferred from HTTP codes: afterwards it runs SQL checks across the booking, event and
payment databases. No seat is in two active bookings, every booked seat belongs to a confirmed booking, and no booking
has two payments. The split line shows how many losers Redis stopped versus MySQL."

Optional: tick **Also pay for every winner**. You'll see confirmed bookings and failed payments, depending on the
provider mode.

## Likely questions

| Question | Short answer |
|---|---|
| Why both Redis and MySQL? | Redis absorbs the crowd cheaply (most losers never touch MySQL); MySQL's conditional update is the guarantee. If Redis is down or slow, the filter fails open and correctness still holds. |
| What if Kafka is down? | State changes and their events commit together in the outbox; the relay retries until Kafka is back. Nothing is lost, only delayed. |
| Why an idempotency key? | Retries and double-clicks replay the stored answer instead of creating a second booking or charge. |
| What does the reconciler do? | It looks up uncertain payments at the provider and settles them: confirm, or refund if the hold expired. |
| Is the page real-time? | It polls every 1 s (Kafka lag and metrics every 10 s). The system has no push channel for this; the page says so. |
| How would it scale? | Shard the Redis locks by event, partition Kafka by booking id (already the key), scale booking-service horizontally (2 replicas here), and add read replicas for the seat map. |

## Honest limits to mention if asked

- The demo harness runs only under `npm run dev`. It reads the local `.env` server-side and uses
  `docker compose exec` for read-only views of Redis, MySQL and Kafka. Secrets never reach the browser; each buyer
  panel receives only its own demo account's token.
- The payment provider is a fake test double; its behaviour switch is internal-network only.
- Load numbers are from one laptop running 13 containers, not a benchmark.
