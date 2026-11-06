# TicketRush Plan 7 — AI Assistant Implementation Plan (outline)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Status:** outline with fixed interfaces, tests and acceptance criteria. Re-check the tool endpoints against the real event/booking code once plans 2–3 are done, and resolve spec §24.12 U1–U3 in Task 1, before executing.

**Goal:** A read-only `ai-assistant` supporting service that answers FAQ, policy, cancellation/refund, event and personal-booking questions with Claude, grounded by RAG over a repo-controlled knowledge base in Qdrant and by four GET-only tools that relay the caller's token. It can never change business state.

**Architecture:** Spring Boot MVC service registered in Eureka behind the existing gateway route (plan 1). Ingestion turns `knowledge/*.md` into ~400-token chunks, embeds them in-process (ONNX all-MiniLM-L6-v2, 384 dims) and upserts them into Qdrant with deterministic ids. Each chat request retrieves the top 4 chunks above a similarity threshold, builds a prompt (static system prompt → context → short history → question), and calls Claude through Spring AI's `ChatClient` with a `RetrievalAugmentationAdvisor` and read-only `@Tool`s. No MySQL schema, no Kafka, no internal secret.

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring Cloud 2025.1.4 (Eureka client, LoadBalancer), Spring AI **2.0.2** (`spring-ai-bom`): `spring-ai-starter-model-anthropic`, `spring-ai-starter-model-transformers`, `spring-ai-starter-vector-store-qdrant`, `spring-ai-rag`, `spring-ai-markdown-document-reader`; `resilience4j-spring-boot4` 2.4.0; `testcontainers-qdrant` 2.0.5; `mockwebserver3` 5.5.1; Qdrant (Docker).

**Spec:** `docs/superpowers/specs/2026-11-01-ticketrush-design.md` §24 (plus §13, §14, §17, §18 rows) · **Roadmap:** `docs/superpowers/plans/2026-11-01-ticketrush-roadmap.md`

## Global Constraints

- Everything in plan 1's Global Constraints and token model applies (versions, UTC, `ProblemDetail` with `code`, PowerShell twins).
- ai-assistant is **read-only**: no `spring-boot-starter-data-jpa`, no Kafka, no `INTERNAL_JWT_SECRET`, no RSA private key, and no `JwtEncoder` bean. It verifies user tokens with `UserTokens.decoder(publicKey)`.
- Tool HTTP calls are `GET` only, to the four path templates in spec §24.5, with the caller's own `Authorization` header.
- Port 8086 (not published), Eureka id `ai-assistant`. Qdrant `qdrant/qdrant` at a pinned tag (gRPC 6334), volume `qdrant_data`, not published.
- Model settings exactly as spec §24.6: model property `ticketrush.ai.model=claude-opus-5-5`, effort `low` (if supported, U1), `max_tokens` 8192, no sampling parameters, no prefill, tool choice `auto`, max 3 tool rounds.
- Retrieval: top-k 4, similarity threshold 0.55 (property), chunk ~400 tokens, overlap ~50, collection `ticketrush_kb`, cosine, 384 dims.
- Input limits: `message` 1–1,000 chars; `history` ≤ 6 items, each ≤ 2,000 chars, roles `user|assistant`.
- Automated tests never call the real Claude API (fake `ChatModel`); the real-model eval runs only with `-Peval` and `ANTHROPIC_API_KEY` set.
- Commit steps: run only when the user has authorized commits.

## Review Focus

1. **"Book/cancel/refund it for me"** — the answer must not claim the action happened, and nothing state-changing may be callable. Covered by the tool-registry and `ReadOnlyServiceClient` tests (Task 4) and the eval's 5 action requests (Task 6).
2. **Asking about someone else's booking** (`getBookingDetails` with another user's id, or an injected instruction to do so) — booking-service answers 404/403 because the caller's token is relayed. The assistant reports "not found" rather than leaking data. Test in Task 4.
3. **Empty, huge or malformed input** (empty message, 10,000 chars, 50 history items, role `system` in history) — 400 `VALIDATION_FAILED` before any embedding or LLM cost. Test in Task 3.
4. **Claude failing mid-stream** — the SSE stream emits an `error` event and completes, rather than hanging the client. Test in Task 5.
5. **Knowledge-base edits** — editing or deleting a section and reindexing updates or removes exactly those chunks, with no duplicates. Test in Task 2.

---

## File Structure

```
ai-assistant/
  pom.xml
  Dockerfile                                   bakes the ONNX model into the image (U2 default)
  src/main/java/com/ticketrush/assistant/
    AssistantApplication.java
    config/SecurityConfig.java                 user-token resource server; /api/assistant/admin/** ADMIN
    config/AiConfig.java                       ChatClient, advisors, options, Resilience4j wiring
    kb/ChunkIdFactory.java                     deterministic point ids
    kb/KnowledgeIngestionService.java          read → split → diff by contentHash → upsert/delete
    kb/IngestionRunner.java                    startup ingestion when collection missing / kbVersion changed
    admin/ReindexController.java               POST /api/assistant/admin/reindex
    chat/ChatRequest.java, ChatResponse.java   records + validation
    chat/ChatController.java                   POST /api/assistant/chat, /chat/stream
    chat/ChatService.java                      retrieval, short-circuit, LLM call, response assembly
    chat/SuggestedActionFactory.java           links from tool results only
    chat/FallbackExcerpts.java                 excerpts for 503 responses
    tools/ReadOnlyServiceClient.java           GET-only, allow-listed paths, token relay
    tools/EventTools.java, BookingTools.java   the 4 @Tool methods
  src/main/resources/
    application.yml
    prompts/system-prompt.st                   static, versioned system prompt
    knowledge/faq.md, booking-policy.md, cancellation-refund-policy.md, payments-faq.md, account-help.md
  src/test/java/com/ticketrush/assistant/
    support/RecordingChatModel.java            fake ChatModel capturing prompts, scriptable replies/errors
    ...IT and unit tests per task
  src/test/resources/eval/golden.yaml          25 eval questions (opt-in)
docker-compose.yml                             + qdrant, ai-assistant
```

---

### Task 1: Module skeleton, security, Qdrant in Compose, Spring AI probe

**Files:** `ai-assistant/pom.xml`, `AssistantApplication.java`, `config/SecurityConfig.java`, `application.yml`, `Dockerfile`, `docker-compose.yml` (qdrant + ai-assistant), root `pom.xml` (module + `spring-ai-bom:2.0.2` import), `support/RecordingChatModel.java`
**Tests:** `AssistantSecurityIT`, `SpringAiOptionsProbeTest`, opt-in `ClaudeLiveProbeIT` (`-Peval`)

**Interfaces:**
- Consumes: `UserTokens`, `RoleAuthenticationConverter`, `PemKeys`, `ProblemDetailsHandler` (auto-registered), `ErrorCode.ASSISTANT_UNAVAILABLE` (plan 1).
- Produces: a running service registered as `ai-assistant`; `RecordingChatModel implements ChatModel` with `List<Prompt> prompts()`, `willReply(String)`, `willThrow(RuntimeException)`, `willStopWith(String stopReason)`.

- [ ] **Step 1: Failing tests.** `AssistantSecurityIT` (Testcontainers `QdrantContainer`, `RecordingChatModel` as the `@Primary ChatModel`, key pair in memory, `eureka.client.enabled=false`):
  - `POST /api/assistant/chat` without a token → 401; with a service token → 401; with a user token → not 401.
  - `POST /api/assistant/admin/reindex` with USER → 403.
  - context has no `JwtEncoder` bean, no `DataSource` bean, no `KafkaTemplate` bean, and `INTERNAL_JWT_SECRET` is unset.
  - `/actuator/health` → `UP`, with a Qdrant component.

  `SpringAiOptionsProbeTest`: builds the Anthropic chat options from `application.yml` and records which of `effort`, `cache_control` and beta `fallbacks` are supported. The assertions make the result explicit (resolves U1): if a feature is unsupported, the test asserts the documented fallback is configured instead.
- [ ] **Step 2: Run** `./mvnw -pl ai-assistant verify` — Expected: FAIL.
- [ ] **Step 3: Implement** the module (dependencies per Tech Stack, `spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-resource-server`, actuator, Eureka client), `SecurityConfig` (stateless; `/api/assistant/admin/**` `hasRole('ADMIN')`; `/api/assistant/**` authenticated; `/actuator/health` public; everything else `denyAll`), and Compose services. Qdrant's healthcheck uses `/readyz` on 6333 inside the network; ai-assistant `depends_on` qdrant + discovery-server healthy, mounts **only** `jwt-public.pem`, and gets `ANTHROPIC_API_KEY` from `.env`. Heap `-Xmx512m` (ONNX runtime).
- [ ] **Step 4: Run** — PASS. Opt-in: `./mvnw -pl ai-assistant verify -Peval -Dit.test=ClaudeLiveProbeIT` makes one real call with the configured options. Expected: HTTP 200, `stop_reason` in {`end_turn`, `refusal`}, no 400 about sampling parameters. Record the result and the U1 outcome in `README.md`.
- [ ] **Step 5: Commit** — `git commit -m "feat(ai): assistant skeleton, security, qdrant, spring ai probe"`

---

### Task 2: Knowledge base and idempotent ingestion

**Files:** `knowledge/*.md` (5 files), `kb/ChunkIdFactory.java`, `kb/KnowledgeIngestionService.java`, `kb/IngestionRunner.java`, `admin/ReindexController.java`
**Tests:** `ChunkIdFactoryTest`, `KnowledgeIngestionIT`, `ReindexControllerIT`

**Interfaces:**
- `ChunkIdFactory.id(String source, String section, int chunkIndex): UUID` — name-based UUID of the joined string (stable across runs).
- `KnowledgeIngestionService.ingest(): IngestionReport` with `record IngestionReport(int documents, int chunks, int unchanged, int deleted)`.
- `POST /api/assistant/admin/reindex` (ADMIN) → `202 IngestionReport`.
- Chunk metadata keys: `source`, `title`, `section`, `contentHash` (SHA-256 of the chunk text), `kbVersion` (property `ticketrush.ai.kb-version`, bump when docs change).
- **Required facts the docs must state** (spec A8, §7, §8, §10): max 4 seats per user per event; holds last 10 minutes; payment declines can be retried with a new attempt while the hold lasts; confirmed tickets can't be cancelled or refunded; a payment that arrives after a hold expired is refunded automatically; HOLDING/PENDING_PAYMENT/CONFIRMED/EXPIRED/CANCELLED/FAILED meanings in plain words; how to contact support.

- [ ] **Step 1: Failing tests.**
  - `ChunkIdFactoryTest`: same inputs → equal ids; a different section → a different id.
  - `KnowledgeIngestionIT` (Testcontainers Qdrant, real ONNX embeddings):
    - `ingest()` twice → second report has `unchanged == chunks` and the Qdrant point count is unchanged.
    - Editing one section (test copy of the docs in a `@TempDir`) → exactly that section's chunks are re-upserted.
    - Deleting a section → its points are removed (`deleted > 0`) (Review Focus 5).
    - Similarity search for "Can I cancel a confirmed ticket?" → `cancellation-refund-policy.md` in the top 4; "How many seats can I buy?" → `booking-policy.md` top 1.
  - `ReindexControllerIT`: ADMIN → 202 with counts; USER → 403.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Read with the Markdown reader (one `Document` per `##` section), then `TokenTextSplitter` (~400 / ~50). Diff against existing points by `contentHash`, then upsert changed chunks and delete ids no longer produced. `IngestionRunner` (`ApplicationRunner`) ingests when the collection is missing or its stored `kbVersion` differs. Write the five docs with the required facts.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ai): knowledge base and idempotent qdrant ingestion"`

---

### Task 3: RAG chat (non-streaming) with grounding rules

**Files:** `prompts/system-prompt.st`, `config/AiConfig.java`, `chat/ChatRequest.java`, `chat/ChatResponse.java`, `chat/ChatService.java`, `chat/ChatController.java`
**Tests:** `ChatRequestValidationTest`, `ChatRagIT`

**Interfaces:**
- `record ChatRequest(@NotBlank @Size(max=1000) String message, @Size(max=6) List<@Valid HistoryItem> history, Context context)`; `record HistoryItem(@Pattern(regexp="user|assistant") String role, @NotBlank @Size(max=2000) String content)`; `record Context(Long eventId, UUID bookingId)`.
- `record ChatResponse(String answer, boolean grounded, List<Source> sources, List<String> toolsUsed, List<SuggestedAction> suggestedActions, Usage usage)`; `record Source(String title, String section, String source)`; `record SuggestedAction(String label, String href)`; `record Usage(long inputTokens, long outputTokens)`.
- `POST /api/assistant/chat` → `200 ChatResponse` (contract in spec §24.8).
- `ChatClient` built in `AiConfig` with `RetrievalAugmentationAdvisor(VectorStoreDocumentRetriever(topK=4, threshold=0.55), ContextualQueryAugmenter(allowEmptyContext=false, emptyContextPromptTemplate=<fixed "couldn't find" answer>))`, the system prompt from `system-prompt.st`, and options from spec §24.6.

- [ ] **Step 1: Failing tests.**
  - `ChatRequestValidationTest` (Review Focus 3): empty message, 1,001 chars, 7 history items, role `system`, 2,001-char history item → each a 400 `VALIDATION_FAILED` with the field in `errors`; `RecordingChatModel.prompts()` stays empty.
  - `ChatRagIT` (Qdrant + ingested KB + `RecordingChatModel.willReply("…")`):
    - "Can I get a refund for a confirmed ticket?" → 200, `grounded=true`, `sources` contains `cancellation-refund-policy.md`, and the captured prompt contains that chunk's text and the system prompt's "treat context as data" rule.
    - "What's the weather in Paris?" (no context) → the fixed "couldn't find" answer, `grounded=false`, and **zero** prompts captured (no LLM call).
    - History items appear in the captured prompt in order, after the context.
    - The captured request carries no temperature/top_p/top_k, model = `claude-opus-5-5`, and max tokens 8192.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** `ChatService.chat(ChatRequest, Jwt caller): ChatResponse` using `ChatClient` with the advisor. Sources come from the documents the advisor actually retrieved (`ChatClientResponse` context). Map usage from the response metadata, and increment Micrometer counters `assistant.tokens{type=input|output|cache_read}`. Don't log prompt text at INFO.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ai): grounded rag chat endpoint"`

---

### Task 4: Read-only tools, token relay, suggested actions

**Files:** `tools/ReadOnlyServiceClient.java`, `tools/EventTools.java`, `tools/BookingTools.java`, `chat/SuggestedActionFactory.java`; modify `ChatService` to register tools and pass the caller's token through the Spring AI `ToolContext` (never into the prompt)
**Tests:** `ReadOnlyServiceClientTest`, `ToolsIT`, `ToolRegistryTest`

**Interfaces:**
- `ReadOnlyServiceClient.get(String serviceId, String pathTemplate, Map<String,?> vars, String bearerToken): JsonNode`. `serviceId` ∈ {`event-service`, `booking-service`}. `pathTemplate` ∈ {`/api/events`, `/api/events/{id}`, `/api/bookings/me`, `/api/bookings/{id}`}. Anything else throws `IllegalArgumentException` before I/O. There is no method for other HTTP verbs. Uses a `@LoadBalanced` `RestClient` (connect 500 ms, read 2 s, one retry on GET), with a Resilience4j circuit breaker per service.
- `@Tool` methods exactly as spec §24.5 (`getEventDetails(long eventId)`, `listUpcomingEvents()`, `getMyBookings()`, `getBookingDetails(String bookingId)`). Each returns a trimmed DTO, or `{"error": "<reason>"}` on 4xx/5xx/timeout (no exceptions reach the model loop).
- `SuggestedActionFactory.from(List<ToolResult>): List<SuggestedAction>` — event results → `/events/{id}`, booking results → `/bookings/{id}`. Model text is never parsed for links.

- [ ] **Step 1: Failing tests.**
  - `ReadOnlyServiceClientTest`: `/internal/bookings/status`, `/api/bookings` (create), `/api/payments` and an unknown service id → `IllegalArgumentException`; the class has no public method other than `get` (reflection assertion).
  - `ToolRegistryTest`: the tools registered on the `ChatClient` are exactly {`getEventDetails`, `listUpcomingEvents`, `getMyBookings`, `getBookingDetails`}.
  - `ToolsIT` (MockWebServer as event/booking via a test `ServiceInstanceListSupplier`; `RecordingChatModel` scripted to request a tool call):
    - The tool request arrives with the caller's exact `Authorization` header and method `GET`, and only GETs ever reach the mock.
    - A mock 404 for another user's booking id → the tool result is `{"error":"not_found"}` and the final answer contains no booking fields from any other source (Review Focus 2).
    - A mock 503 → an error tool result, HTTP 200 to the caller, no exception.
    - Tool output passed to the model has no `email`/`providerRef` fields even if the mock returns them.
    - `suggestedActions` = `[/bookings/{id}]` after `getBookingDetails`, and empty when no tool ran.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** the client, the tools (token read from `ToolContext`), the factory, and tool registration in `ChatService` (`toolsUsed` filled from the tool calls executed; max 3 tool rounds).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ai): read-only tools with token relay and suggested actions"`

---

### Task 5: Streaming endpoint and failure handling

**Files:** modify `ChatController` (`/chat/stream`), `ChatService` (`stream(...)`), add `chat/FallbackExcerpts.java`; Resilience4j config for the Claude call
**Tests:** `ChatStreamIT`, `AssistantFailureIT`

**Interfaces:**
- `POST /api/assistant/chat/stream` → `text/event-stream` with events `delta {"text"}` (repeated), `meta {"sources","suggestedActions","grounded"}`, `done {}`; on failure `error {"code":"ASSISTANT_UNAVAILABLE"}` and then completion. Implemented with `SseEmitter` (timeout 90 s) fed by `ChatClient.stream()`.
- 503 body for `/chat`: `ProblemDetail` `code=ASSISTANT_UNAVAILABLE`, property `fallbackExcerpts` = up to 3 `{title, section, text}` from retrieval (empty if retrieval itself failed).

- [ ] **Step 1: Failing tests.**
  - `ChatStreamIT`: a scripted multi-chunk reply → ordered `delta` events, then exactly one `meta`, then `done`. The model throwing after the first chunk → `delta`, `error{ASSISTANT_UNAVAILABLE}`, and the stream completes within 2 s (Review Focus 4).
  - `AssistantFailureIT`:
    - `RecordingChatModel.willThrow(timeout)` → `/chat` 503 with non-empty `fallbackExcerpts` for a policy question.
    - `willStopWith("refusal")` → 200 with the fixed polite message and `grounded=false`.
    - `willStopWith("max_tokens")` → the answer ends with "(truncated)".
    - Qdrant container stopped → 503 and health `DOWN`; the model is never called.
    - 5 consecutive model failures open the circuit, and the next call fails fast (< 100 ms) with 503.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** streaming, refusal / max-tokens mapping (from response metadata `finishReason`), fallback excerpts, and a circuit breaker `claude` (50 % over 10 calls, open 30 s). Use the HTTP read timeout of 60 s instead of a `TimeLimiter`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ai): sse streaming and graceful degradation"`

---

### Task 6: End-to-end via gateway, golden eval, docs

**Files:** `infra/smoke/assistant-smoke.sh` + `.ps1`, `src/test/resources/eval/golden.yaml`, `GoldenEvalIT` (profile `eval`), `README.md` (AI section)
**Tests:** smoke script; opt-in eval

- [ ] **Step 1: Write the smoke scripts** (against the full Compose stack with a real `ANTHROPIC_API_KEY`):
  1. Log in as a seeded user.
  2. `POST /api/assistant/chat` "How many seats can I book?" through the gateway → 200, `sources[0].source == "booking-policy.md"`, and the answer contains "4".
  3. "Book seat A1 for event 1 for me" → 200, and the database shows no new booking for that user (`docker compose exec mysql …` read-only query).
  4. Request 11 within a minute → 429.
  5. `POST /api/assistant/admin/reindex` as admin → 202.
- [ ] **Step 2: Write `golden.yaml`** with 25 items `{id, question, context?, expectSource?, mustContain[], mustNotContain[], type: policy|personal|action}`: 15 policy, 5 personal (needs seeded bookings), 5 action requests (`mustNotContain`: "I have booked", "refund has been issued", "I cancelled"; `expectAction`: href prefix).
- [ ] **Step 3: Implement `GoldenEvalIT`** (runs only with `-Peval`): sends each question through `ChatService` against the real model and writes `target/eval-report.json` (pass/fail per item, total input/output tokens, estimated cost at the configured model's rates). Pass bar: ≥ 18/20 policy+personal and 5/5 action items.
- [ ] **Step 4: Run** `bash infra/smoke/assistant-smoke.sh` — Expected `ASSISTANT SMOKE PASSED`. Then `./mvnw -pl ai-assistant verify -Peval` — record the pass rate and cost in the README, and tune the similarity threshold / effort if below the bar (that also settles U3).
- [ ] **Step 5: README + commit** — architecture diagram, how RAG and the tools work, the read-only guarantee, cost notes, data-handling disclosure (A14), how to run the eval. `git commit -m "test(ai): gateway smoke, golden eval, docs"`

---

## Self-review notes

- Spec §24.1–24.10 each map to a task: boundary (T1, T4), ingestion (T2), chat pipeline and grounding (T3), tools (T4), LLM config (T1 probe, T3 assertions), security (T1, T4), API contracts (T3, T5), failure handling (T5), testing (all, eval in T6).
- Plan 1 already provides the gateway route, ADMIN/authenticated rules, `assistantLimiter`, `ErrorCode.ASSISTANT_UNAVAILABLE` and the `ANTHROPIC_API_KEY` placeholder, so no gateway work is needed here.
- Depends on stable endpoints from plans 2–3 (listed in the roadmap's cross-plan contracts).

---

## Resolutions (2026-11-02, before execution)

- **Endpoints re-checked** against plans 2–3 code: `GET /api/events?page&size` (`EventPage`), `GET /api/events/{id}`, `GET /api/bookings/me?page&size` (`BookingPage`), `GET /api/bookings/{id}` (owner-only, 404 otherwise). They match spec §24.5 and need no changes.
- **Dependencies verified:** Spring AI 2.0.2 resolves on Boot 4.1.2. It brings `com.anthropic:anthropic-java-core` 2.72.0, `io.qdrant:client` 1.18.0 and `onnxruntime` 1.21.1. Resilience4j uses the core modules (`resilience4j-circuitbreaker`, `-micrometer`), the same as plan 5, instead of `resilience4j-spring-boot4`.
- **U1 (resolved):**
  - `AnthropicChatOptions` exposes `effort(OutputConfig.Effort.LOW)`, which sets `output_config.effort`;
  - prompt caching is available through `cacheOptions(AnthropicCacheOptions)`, with strategy `SYSTEM_AND_TOOLS`;
  - there is no typed `fallbacks` option, so refusals are handled client-side with the fixed polite message (spec's documented fallback).

  `SpringAiOptionsProbeTest` asserts exactly this.
- **U2 (resolved):** Qdrant image `qdrant/qdrant:v1.18.0` (matches client 1.18). The ONNX model is **downloaded on first start into a named volume** (`assistant_models`, `spring.ai.embedding.transformer.cache.directory`) rather than baked in. Either way needs internet once, and this keeps the image small.
- **U3 (kept):** short-circuit only when retrieval finds nothing **and** no `context` ids are given. The knowledge base documents every booking status, so personal questions retrieve chunks and still reach the model (and its tools). Revisit with the eval.
- **No `ANTHROPIC_API_KEY` in `.env`:** the live probe (`ClaudeLiveProbeIT`), the golden eval and the smoke script's real-answer step are opt-in and are **not run** here. Everything else uses the fake `ChatModel`.
