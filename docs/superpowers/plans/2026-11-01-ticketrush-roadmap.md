# TicketRush — Implementation Roadmap

**Spec:** `docs/superpowers/specs/2026-11-01-ticketrush-design.md` (v3: core + §23 frontend + §24 AI Assistant)

The spec spans eight subsystems. Each gets its own plan, written when the previous
one is finished so it can reference real code. Every plan ends with working,
tested software. Order follows spec §21; never start a plan before the previous
plan's "Done when" passes.

| # | Plan | Spec sections | Done when | Status |
|---|---|---|---|---|
| 1 | **Foundation** — Maven multi-module repo, `common` lib, Docker Compose infra (MySQL per-schema users, Redis, Kafka KRaft), Eureka, user-service + RS256 JWT, API gateway (verify-only auth, route authorization, `/internal/**` deny, rate limit, CORS) | §3, §5, §6.1, §13, §15 (rate limit, LB) | `infra/smoke/foundation-smoke.(sh|ps1)` passes against a cold `docker compose up` | **Written:** `2026-11-01-ticketrush-01-foundation.md` |
| 2 | **Event service** — events + seat generation, seat map, catalog cache, internal idempotent `hold` with sale-window check, `booking.confirmed/cancelled` consumers with dedupe | §6.2, §7.2, §8.3–8.4 (event side), §9.2, §11.3, §15 | Testcontainers tests incl. 500-thread single-seat hold test (DB only) pass | **Written:** `2026-11-02-ticketrush-02-event-service.md` |
| 3 | **Booking service** — Approach A first, then Redis Lua layer; quota; state machine; create/cancel; sweeper; outbox + relay; `payment.succeeded` consumer; batch status endpoint; 2 instances | §6.3, §7.1, §8.1, §8.3, §9.1, §9.3, §11.2, §12 | Concurrency proofs 1–4 (§16.1) pass | **Done (2026-11-02):** `2026-11-02-ticketrush-03-booking-service.md` — `mvnw verify` green (115 tests), both smoke scripts pass on cold Compose |
| 4 | **Payment service + fake provider** — PROCESSING-first flow, generated-column uniqueness, provider stub (decline / timeout-after-charge / down), outbox, refund consumer | §6.4, §6.5, §7.3, §8.2, §10 | Concurrency proof 6 + ambiguous-timeout test pass; end-to-end happy path via gateway | **Done (2026-11-02):** `2026-11-02-ticketrush-04-payment-service.md` — `mvnw verify` green, both smoke scripts pass (book → pay → CONFIRMED → BOOKED) |
| 5 | **Reliability** — seat reconciler, payment reconciler (a)(b)(c), Resilience4j configs + degradation modes, DLT handling, cleanup jobs | §10, §11.5, §12, §14, §17 | Proof 5 + duplicate-delivery + outbox-kill tests pass | **Done (2026-11-02):** `2026-11-02-ticketrush-05-reliability.md` — `mvnw verify` green (200 tests), smoke + Redis-down chaos check pass |
| 6 | **Load testing & proof** — user seeding, k6 S1–S5, metrics capture, SQL invariant script, README with measured results | §16.2–16.4 | S1 invariants pass on the Compose stack; numbers recorded | **Done (2026-11-02):** `2026-11-02-ticketrush-06-load-testing.md` — S1, S2, S3 pass and are recorded in README; **S4 and S5 not yet run** (host memory) |
| 7 | **AI Assistant** — `ai-assistant` supporting service: Spring AI 2.0.2, Claude, local ONNX embeddings, Qdrant RAG over the repo knowledge base, 4 read-only tools with token relay, chat + SSE endpoints, admin reindex, golden eval | §24 (+ §13, §14, §17 rows) | Integration tests (Testcontainers Qdrant + fake `ChatModel`) and safety/security tests pass; via the gateway a real chat answer cites a policy source; opt-in eval run recorded | **Done (2026-11-02), except the real-key items:** `2026-11-01-ticketrush-07-ai-assistant.md` — 36 tests green, assistant smoke passes through the gateway; **real Claude answer + golden eval not run (no ANTHROPIC_API_KEY)** |
| 8 | **Demo frontend** — React + Vite UI per §23, incl. assistant chat panel | §23, §24.8 | Playwright smoke login → hold → pay → confirmed, plus one assistant question with a source chip, passes | **Done (2026-11-03):** `2026-11-02-ticketrush-08-frontend.md` |

## Cross-plan contracts (fixed now so plans stay consistent)

- **Repo layout:** root `pom.xml` (packaging `pom`); modules `common`, `discovery-server`,
  `api-gateway`, `user-service`, `event-service`, `booking-service`, `payment-service`,
  `fake-payment-provider`, `ai-assistant`; `frontend/` (npm, not a Maven module); `infra/`.
- **Transactional vs supporting:** exactly four transactional business services (user, event, booking, payment).
  `ai-assistant` is a read-only supporting service: no business schema, no Kafka, no internal secret, GET-only tool calls.
- **Base package:** `com.ticketrush.<module>` (e.g. `com.ticketrush.booking`); shared code in `com.ticketrush.common`.
- **Ports:** gateway 8080, eureka 8761, user 8081, event 8082, booking 8083/8084, payment 8085, ai-assistant 8086, provider 8090, Qdrant 6333/6334 (unpublished), frontend 5173.
- **Eureka service ids:** `user-service`, `event-service`, `booking-service`, `payment-service`, `ai-assistant`.
- **Endpoints the assistant's tools depend on (plans 2–3 must keep them stable):** `GET /api/events` (paged, `page`/`size`),
  `GET /api/events/{id}` (incl. available-seat count), `GET /api/bookings/me` (paged, `size`), `GET /api/bookings/{id}`
  (owner-only; includes `status`, seats, `expires_at`, `failure_reason`). All accept user tokens relayed service-to-service.
- **Gateway edge rules for the assistant** are in plan 1 (route, auth, `assistantLimiter`).
- **AI versions:** Spring AI 2.0.2 (`spring-ai-bom`), `testcontainers-qdrant` 2.0.5; model `claude-opus-5-5` (configurable).
- **Errors:** RFC 9457 `ProblemDetail` with property `code` from `com.ticketrush.common.error.ErrorCode`.
- **Auth (revised in plan 1 v2):** user JWT = **RS256**, signed only by user-service (private key mounted only there),
  verified everywhere with the public key; claims `iss`=`ticketrush-user-service`, `aud`=`ticketrush-api`,
  `token_use`=`user`, `sub`=userId, `role`=`USER|ADMIN`, 1 h. Service JWT = HS256 with `INTERNAL_JWT_SECRET`
  (event/booking/payment only), `iss`=`aud`=`ticketrush-internal`, `token_use`=`service`, `sub`=service id,
  `role`=`SERVICE`, 5 min; accepted only by each service's `/internal/**` filter chain. Helpers in
  `com.ticketrush.common.security.{UserTokens,ServiceTokens,RoleAuthenticationConverter,PemKeys}`.
- **Versions:** Spring Boot 4.1.2, Spring Cloud 2025.1.4 (see plan 1 "Version decision").
- **Kafka envelope:** `com.ticketrush.common.events.EventEnvelope` (defined in plan 1).
- **IDs:** `com.ticketrush.common.id.UuidV7.next()`; stored `BINARY(16)`.
- **Time:** UTC everywhere.
- **Commits:** plans include commit steps; run them only once the user has authorized commits for implementation.
