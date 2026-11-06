# TicketRush Plan 1 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A runnable skeleton: Maven multi-module repo, shared `common` library, Docker Compose infrastructure, Eureka, a working user-service that signs JWTs, and an API gateway that verifies (but cannot sign) JWTs, enforces public/authenticated/ADMIN route rules, rate-limits, blocks `/internal/**` and routes via Eureka.

**Architecture:** Spring Boot services registered in Eureka behind Spring Cloud Gateway (WebFlux). MySQL runs as one container with one schema and one DB user per service; Redis backs the gateway rate limiter. User tokens are RS256: only user-service holds the private key, every verifier holds only the public key. Service-to-service tokens are a separate token type (own issuer, audience, algorithm and secret) accepted only on `/internal/**`. Shared contracts (error codes, token verification rules, ids, event envelope) live in a small `common` jar.

**Tech Stack:** see "Version decision" below. Java 21, Maven wrapper, MySQL 8.0, Redis 7, Kafka KRaft (started now, used from plan 2), Flyway, Spring Security OAuth2 Resource Server (Nimbus), JUnit 5, AssertJ, Testcontainers 2, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-11-01-ticketrush-design.md` · **Roadmap:** `docs/superpowers/plans/2026-11-01-ticketrush-roadmap.md`

**Revision:** v2 — technical review corrections (versions verified, JWT signing boundary, gateway authorization, handler discovery, BCrypt byte limit, meaningful DB-isolation test, Flyway MySQL module, `common` dependency design, deterministic rate-limit tests, internal endpoint security). Change list at the end.

---

## Version decision (verified 2026-11-01 against Maven Central metadata, spring.io, springdoc README, Spring Boot 4.0 migration guide)

| Component | Version | Evidence / reason |
|---|---|---|
| Java | 21 | spec |
| Spring Boot | **4.1.2** (parent `spring-boot-starter-parent`) | latest GA 4.x on Maven Central; 4.1 is the supported line through 2027 (4.0 support ends late 2026). Same Boot 4 generation the spec chose. |
| Spring Cloud | **2025.1.4** (`spring-cloud-dependencies`) | spring.io table: 2025.1.x supports Boot 4.0.x **and 4.1.x from 2025.1.2**. Brings Gateway 5.0.4, Netflix (Eureka) 5.0.3, CircuitBreaker 5.0.3. |
| Gateway starter | `org.springframework.cloud:spring-cloud-starter-gateway-server-webflux` | the WebFlux server starter (built-in `RedisRateLimiter`); old `spring-cloud-starter-gateway` name not used |
| MVC starter | `org.springframework.boot:spring-boot-starter-webmvc` | Boot 4 renamed `spring-boot-starter-web` (old name = deprecated alias) |
| Resource server starter | `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server` | Boot 4 name; `spring-boot-starter-oauth2-resource-server` is deprecated |
| Test starters | `spring-boot-starter-webmvc-test`, `spring-boot-starter-webflux-test`, `spring-boot-starter-security-test`, `spring-boot-testcontainers` | Boot 4 per-technology test starters; MockMvc needs `@AutoConfigureMockMvc`, WebTestClient needs `@AutoConfigureWebTestClient` on `@SpringBootTest` |
| Flyway | `spring-boot-starter-flyway` **+** `org.flywaydb:flyway-mysql` (Boot-managed Flyway 12.4.0) | Boot 4 needs the Flyway starter for auto-configuration; Flyway ≥ 10 ships MySQL support only in the separate `flyway-mysql` module |
| MySQL driver | `com.mysql:mysql-connector-j` (Boot-managed 9.7.0), `runtime` scope | — |
| Testcontainers | 2.0.5 (Boot-managed); artifacts `org.testcontainers:testcontainers-mysql`, `testcontainers-junit-jupiter`; class `org.testcontainers.mysql.MySQLContainer` | TC 2 renamed modules/packages (old `org.testcontainers:mysql` is 1.x) |
| Resilience4j | `io.github.resilience4j:resilience4j-spring-boot4` **2.4.0** | exists on Maven Central, compiled against Boot 4.0.0 autoconfigure — not used until plan 3, probed in Task 1 against 4.1.2 |
| springdoc | `org.springdoc:springdoc-openapi-starter-webmvc-ui` **3.2.0** | springdoc 3.x = Boot 4.x; 3.2.0's parent is Boot 4.1.2 |
| MockWebServer (gateway tests) | `com.squareup.okhttp3:mockwebserver3` **5.5.1**, test scope | not Boot-managed, so pinned |
| JSON | Jackson 3 (Boot 4 default, packages `tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation`) | migration guide |

**Fallback rule (only if Task 1's probe fails):** switch the *whole* repo to Boot **3.5.16** + Spring Cloud **2025.0.x** (latest), `spring-boot-starter-web`, `spring-boot-starter-oauth2-resource-server`, `resilience4j-spring-boot3` 2.4.0, springdoc **2.8.x**, Testcontainers as managed by Boot 3.5 (artifact `org.testcontainers:mysql`, class `org.testcontainers.containers.MySQLContainer`), Flyway as managed + `flyway-mysql`; drop the Boot 4 test starters for `spring-boot-starter-test`. Record the failure and the switch in `README.md`. Never mix trains.

## Security decision: token types and signing boundary

| | **User token** | **Service token** |
|---|---|---|
| Purpose | end users via gateway (`/api/**`) | service → service (`/internal/**`) |
| Algorithm | **RS256** | **HS256** |
| Signing key | RSA private key — **mounted only into user-service** | `INTERNAL_JWT_SECRET` (≥ 32 bytes) — given only to services that call or expose `/internal/**` (event, booking, payment; **never** the gateway or user-service) |
| Verification key | RSA public key — gateway + every service | `INTERNAL_JWT_SECRET` |
| `iss` | `ticketrush-user-service` | `ticketrush-internal` |
| `aud` | `ticketrush-api` | `ticketrush-internal` |
| `token_use` | `user` | `service` |
| `sub` | user id | service id (e.g. `booking-service`) |
| `role` | `USER` \| `ADMIN` | `SERVICE` |
| lifetime | 1 h | 5 min (cached, re-minted < 60 s before expiry) |
| also | `iat`, `jti` | `iat`, `jti` |

Verification rules (both types): signature with the algorithm **pinned** (RS256 decoder never accepts HS256/`none`, and vice versa), `exp`/`nbf` with 30 s clock skew, exact `iss`, `aud` contains the expected value, `token_use` equals the expected value, `sub` and `role` present. Roles are read only from a token that passed all of these.

Consequences:
- The gateway **cannot mint any token** (it has neither the private key nor the internal secret).
- event/booking/payment can mint *service* tokens but **cannot mint user or ADMIN tokens**, and a service token is rejected by every `/api/**` endpoint and by the gateway (wrong algorithm, issuer, audience and `token_use`).
- A user token is rejected on `/internal/**` (each service runs a separate `SecurityFilterChain` for `/internal/**` with the service-token decoder and `hasRole('SERVICE')`).
- Accepted trade-off: services share one internal secret, so one compromised service could impersonate another *service* (not a user). Upgrade path: per-service key pairs or mTLS — documented in README, out of scope.
- Defense in depth for `/internal/**`: (1) gateway has no route for it and `denyAll`s it, (2) service ports are not published by Compose (only gateway 8080 and the Eureka dashboard 8761 are), (3) each service authenticates the service token itself.
- Dev keys: generated locally by `infra/keys/generate-dev-keys.(sh|ps1)` into `infra/keys/` (git-ignored); tests generate key pairs in memory.

## Global Constraints

- Java 21; `spring.threads.virtual.enabled=true` in every MVC service.
- Versions exactly as in "Version decision"; one release train for the whole repo.
- MySQL 8.0 (≥ 8.0.16), InnoDB, default `REPEATABLE READ`; Flyway owns all DDL; JPA `spring.jpa.hibernate.ddl-auto=validate`; `spring.flyway.enabled=true`.
- One schema + one MySQL user per service, granted only on its own schema; each service's datasource URL names only its own schema.
- All timestamps UTC `DATETIME(3)`; JVM `-Duser.timezone=UTC`; JDBC `connectionTimeZone=UTC`.
- JVM heap `-Xmx256m`–`-Xmx384m` per container.
- Tokens exactly as in "Security decision".
- Errors: RFC 9457 `ProblemDetail` with a `code` property.
- Ports: gateway 8080 (published), eureka 8761 (published, dev dashboard), user-service 8081 (**not published**). (ai-assistant 8086 and Qdrant come in plan 7, also unpublished.) Base package `com.ticketrush.<module>`.
- Gateway rate limits (production values): `POST /api/bookings/**` and `POST /api/payments/**` replenish 2/s, burst 5; `/api/assistant/**` 10 requests/min per user, burst 10 (LLM cost control; spec §24.7); all other routes replenish 20/s, burst 40; values are configuration properties so tests can override them. Key = JWT `sub` for authenticated requests, else the socket remote address; `server.forward-headers-strategy=none`.
- Passwords: 8–72 characters **and** ≤ 72 bytes UTF-8 (BCrypt's input limit).
- Commands are written for a Unix shell; on Windows PowerShell run `.\mvnw.cmd` instead of `./mvnw` (same arguments) and the `.ps1` twin of each script. `docker compose` commands are identical.
- Commit steps: run only when the user has authorized commits.

## Review Focus

1. **Email case/whitespace** — `" Alice@X.com "` and `alice@x.com` are one account (register conflict, login succeeds). Tests in Task 5.
2. **Wrong-kind or forged tokens** — expired, malformed, signed by another RSA key, `alg: none`, HS256-signed, a valid *service* token, or a user token with the wrong audience must all give 401 at the gateway and `/api/**`, never 500 and never access. Tests in Tasks 2 and 6.
3. **Long or multi-byte passwords** — a 25-character password of `€` (75 bytes) must be rejected with 400 on register and 401 on login, not a 500 from BCrypt. Tests in Task 5.
4. **Spoofed `X-Forwarded-For`** — changing the header must not give an anonymous client a fresh bucket. Tests in Task 6.
5. **Start-up ordering / missing keys** — cold `docker compose up` must not crash-loop (healthchecks + `depends_on: condition: service_healthy`); a missing or malformed key file must stop user-service/gateway at startup with a clear message. Tests in Tasks 5–7.

---

## File Structure

```
pom.xml                                   parent POM: versions, BOMs, modules
.mvn/, mvnw, mvnw.cmd                     Maven wrapper
.gitignore                                target/, .env, infra/keys/*.pem, graphify-out/, node_modules/
.env.example                              env vars with dev defaults
docker-compose.yml                        infra + foundation services
infra/keys/generate-dev-keys.sh|.ps1      RSA 2048 dev key pair (PKCS#8 private, X.509 public)
infra/mysql/init/01-schemas.sql           schemas + per-service users/grants
infra/verify-infra.sh|.ps1                proves DB isolation, Redis, Kafka
infra/smoke/foundation-smoke.sh|.ps1      end-to-end smoke via gateway
common/
  pom.xml                                 compile deps only (see Task 2)
  src/main/java/com/ticketrush/common/
    id/UuidV7.java
    error/ErrorCode.java
    error/ApiException.java
    error/ProblemDetailsHandler.java      @RestControllerAdvice (not component-scanned)
    error/CommonErrorAutoConfiguration.java  registers the handler in servlet apps only
    security/TokenClaims.java             constants: issuers, audiences, token_use, claim names
    security/UserTokens.java              RS256 verification rules (validator + servlet decoder)
    security/ServiceTokens.java           HS256 service token minting + verification
    security/RoleAuthenticationConverter.java  role claim → ROLE_*, principal = sub
    security/PemKeys.java                 read RSA public/private keys from a location
    events/EventEnvelope.java
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/test/java/com/ticketrush/common/...
discovery-server/                         Eureka server + Dockerfile
user-service/
  src/main/java/com/ticketrush/user/
    UserServiceApplication.java
    User.java, Role.java, UserRepository.java
    RegisterRequest.java, LoginRequest.java   records; normalize email in compact constructor
    AuthController.java                   /api/users/register, /login, /me
    AuthService.java                      register/login logic
    UserTokenIssuer.java                  RS256 signing — exists ONLY in user-service
    SecurityConfig.java                   /internal/** deny, public endpoints, resource server
    AdminSeeder.java
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__users.sql
  src/test/java/com/ticketrush/user/{AuthFlowIT,FlywayAndHandlerIT}.java
  Dockerfile
api-gateway/
  src/main/java/com/ticketrush/gateway/
    GatewayApplication.java
    GatewaySecurityConfig.java            authorization rules, reactive RS256 decoder, CORS
    RateLimitConfig.java                  strict/default limiters + key resolvers
  src/main/resources/application.yml      routes
  src/test/java/com/ticketrush/gateway/{GatewayIT,RateLimitKeyResolverTest}.java
  Dockerfile
```

---

### Task 1: Repo skeleton and version probe

**Files:**
- Create: `pom.xml`, `.mvn/`, `mvnw`, `mvnw.cmd`, `.gitignore`, `.env.example`, `common/pom.xml`
- Test (temporary): `common/src/test/java/com/ticketrush/common/CompatibilityProbeTest.java`

**Interfaces:**
- Produces: parent POM `com.ticketrush:ticketrush-parent` with parent `spring-boot-starter-parent:4.1.2`; `<dependencyManagement>` imports `spring-cloud-dependencies:2025.1.4` and `io.github.resilience4j:resilience4j-bom:2.4.0`; properties `java.version=21`, `springdoc.version=3.2.0`, `mockwebserver3.version=5.5.1`; managed entries for `springdoc-openapi-starter-webmvc-ui` and `mockwebserver3`. Testcontainers, Flyway and the MySQL driver come from Boot's BOM (no overrides). Module list: `common`.

- [ ] **Step 1: Create parent POM, wrapper, ignore and env files.** Wrapper via `mvn -N wrapper:wrapper`. `.gitignore`: `target/`, `.env`, `infra/keys/*.pem`, `graphify-out/`, `node_modules/`, `.idea/`, `*.iml`. `.env.example`: `MYSQL_ROOT_PASSWORD`, `USER_DB_PASSWORD`, `EVENT_DB_PASSWORD`, `BOOKING_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `ADMIN_EMAIL=admin@ticketrush.dev`, `ADMIN_PASSWORD`, `INTERNAL_JWT_SECRET` (≥ 32 chars; used from plan 2), `JWT_PRIVATE_KEY_FILE=./infra/keys/jwt-private.pem`, `JWT_PUBLIC_KEY_FILE=./infra/keys/jwt-public.pem`, `ANTHROPIC_API_KEY=` (empty placeholder; only ai-assistant reads it, from plan 7).

- [ ] **Step 2: Write the probe test.** `common/pom.xml` temporarily adds **test-scoped**: `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, `spring-cloud-starter-netflix-eureka-client`, `resilience4j-spring-boot4`, `springdoc-openapi-starter-webmvc-ui`, `spring-boot-testcontainers`, `testcontainers-mysql`, `testcontainers-junit-jupiter`. Test properties: `eureka.client.enabled=false`.

```java
@SpringBootTest(classes = CompatibilityProbeTest.App.class, webEnvironment = RANDOM_PORT,
                properties = "eureka.client.enabled=false")
class CompatibilityProbeTest {
    @SpringBootApplication static class App {}
    @Autowired CircuitBreakerRegistry circuitBreakers;   // resilience4j-spring-boot4 auto-config
    @LocalServerPort int port;

    @Test void resilience4jAutoConfigures() { assertThat(circuitBreakers.circuitBreaker("probe")).isNotNull(); }
    @Test void springdocServesApiDocs() {
        var body = RestClient.create("http://localhost:" + port).get().uri("/v3/api-docs").retrieve().body(String.class);
        assertThat(body).contains("\"openapi\"");
    }
    @Test void springCloudContextStarts(ApplicationContext ctx) {        // train matches Boot
        assertThat(ctx.getBeanNamesForType(org.springframework.cloud.client.discovery.DiscoveryClient.class)).isNotEmpty();
    }
    @Test void testcontainers2StartsMysql() {
        try (var mysql = new org.testcontainers.mysql.MySQLContainer("mysql:8.0")) { mysql.start(); assertThat(mysql.isRunning()).isTrue(); }
    }
}
```

- [ ] **Step 3: Run** `./mvnw -pl common test -Dtest=CompatibilityProbeTest` — Expected: 4 tests PASS, and `./mvnw -pl common dependency:tree | grep -E "spring-boot:|spring-cloud-commons"` shows `spring-boot:4.1.2` and a 5.0.x `spring-cloud-commons`. If the probe fails for a version reason, apply the fallback rule from "Version decision" and re-run until PASS.
- [ ] **Step 4: Delete the probe test and its test-only dependencies.** Run `./mvnw -q validate` — Expected: exit 0. Write a `README.md` stub with a "Version decision" section copying the table above (or the fallback actually used).
- [ ] **Step 5: Commit** — `git add -A && git commit -m "build: maven multi-module skeleton, versions verified"`

---

### Task 2: `common` library

**Files:**
- Create: the `common/src/main/...` files in File Structure
- Test: `common/src/test/java/com/ticketrush/common/{UuidV7Test,UserTokensTest,ServiceTokensTest,RoleAuthenticationConverterTest,PemKeysTest,ProblemDetailsHandlerTest}.java`

**Dependency design (`common/pom.xml`):** compile scope only, versions from the Boot/Cloud BOMs — `spring-web` (works under both MVC and WebFlux), `spring-security-oauth2-jose` (Nimbus), `spring-security-oauth2-resource-server` (JWT authentication types), `spring-boot-autoconfigure` (for the conditional handler registration). **No `provided` or `optional` scopes**; no `spring-webmvc`, no Reactor, no JPA, no Kafka. Each service declares its own web stack starter. Test scope: `spring-boot-starter-webmvc-test`.

**Interfaces:**
- Produces:
  - `UuidV7.next(): UUID` — RFC 9562 version 7, monotonic within a JVM.
  - `enum ErrorCode { VALIDATION_FAILED(400), UNAUTHORIZED(401), FORBIDDEN(403), NOT_FOUND(404), EMAIL_TAKEN(409), INVALID_CREDENTIALS(401), SEATS_UNAVAILABLE(409), SEAT_LIMIT_EXCEEDED(409), SALE_NOT_OPEN(409), IDEMPOTENCY_KEY_REUSED(422), PAYMENT_IN_PROGRESS_OR_DONE(409), DECLINED(402), DEPENDENCY_UNAVAILABLE(503), ASSISTANT_UNAVAILABLE(503), INTERNAL_ERROR(500); int status(); }` (`ASSISTANT_UNAVAILABLE` is used by ai-assistant in plan 7)
  - `ApiException(ErrorCode code, String detail)`; `code()`.
  - `ProblemDetailsHandler` (`@RestControllerAdvice`, **in a package no service scans**): `ApiException` → `ProblemDetail` (status from code, `detail`, property `code`); `MethodArgumentNotValidException` → `VALIDATION_FAILED` + property `errors` (field → message); any other `Exception` → `INTERNAL_ERROR` with generic detail (no stack trace or exception message leaked) and the exception logged.
  - `CommonErrorAutoConfiguration` — `@AutoConfiguration`, `@ConditionalOnWebApplication(type = SERVLET)`, `@Import(ProblemDetailsHandler.class)`; listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. So every MVC service gets the handler without component scanning, and the WebFlux gateway never does.
  - `TokenClaims` constants: `USER_ISSUER="ticketrush-user-service"`, `USER_AUDIENCE="ticketrush-api"`, `SERVICE_ISSUER="ticketrush-internal"`, `SERVICE_AUDIENCE="ticketrush-internal"`, `TOKEN_USE="token_use"`, `ROLE="role"`, `USE_USER="user"`, `USE_SERVICE="service"`, `CLOCK_SKEW=Duration.ofSeconds(30)`.
  - `UserTokens.validator(): OAuth2TokenValidator<Jwt>` — timestamps (30 s skew) + issuer + audience + `token_use=user` + `sub`/`role` present.
  - `UserTokens.decoder(RSAPublicKey key): JwtDecoder` — `NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(RS256)` + `validator()`. (Reactive decoders are built by the gateway itself from `validator()`, keeping Reactor out of `common`.)
  - `ServiceTokens.provider(String secret, String serviceId, Clock clock): ServiceTokens.Provider` with `String token()` (claims per Security decision; cached until < 60 s remain).
  - `ServiceTokens.decoder(String secret): JwtDecoder` — HS256 pinned + timestamps + `iss`/`aud`=internal + `token_use=service` + `role=SERVICE`.
  - Both `ServiceTokens` factories throw `IllegalStateException("INTERNAL_JWT_SECRET must be at least 32 bytes")` for shorter secrets.
  - `RoleAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken>` — authority `ROLE_<role>`, principal name `sub`.
  - `PemKeys.publicKey(Resource): RSAPublicKey`, `PemKeys.privateKey(Resource): RSAPrivateKey` — via Spring Security `RsaKeyConverters.x509()` / `.pkcs8()`; missing or unparsable input → `IllegalStateException` naming the location.
  - `record EventEnvelope<T>(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, UUID aggregateId, T payload)`.
- Note: **no class in `common` can sign a user token** (user-token signing lives only in user-service, Task 5).

- [ ] **Step 1: Write failing tests** (test helper `TestKeys` generates RSA 2048 pairs with `KeyPairGenerator`; `TestJwts` signs arbitrary claim sets with a given key/algorithm using `NimbusJwtEncoder` — test code only).

```java
// UuidV7Test
@Test void isVersion7UniqueAndTimeOrdered() {
    var ids = IntStream.range(0, 1000).mapToObj(i -> UuidV7.next()).toList();
    assertThat(ids).allMatch(u -> u.version() == 7);
    assertThat(ids).isSortedAccordingTo(Comparator.comparing(UUID::toString));
    assertThat(new HashSet<>(ids)).hasSize(1000);
}
// UserTokensTest  (keys = TestKeys.rsa(); decoder = UserTokens.decoder(keys.pub()))
@Test void acceptsWellFormedUserToken() {
    var jwt = decoder.decode(TestJwts.rs256(keys.priv(), validUserClaims("42", "ADMIN")));
    assertThat(jwt.getSubject()).isEqualTo("42");
}
@Test void rejectsEveryForgedOrWrongKindToken() {           // Review Focus 2
    var bad = List.of(
        TestJwts.rs256(TestKeys.rsa().priv(), validUserClaims("42", "ADMIN")),   // other key
        TestJwts.rs256(keys.priv(), validUserClaims("42","USER").expiresAt(Instant.now().minusSeconds(120))),
        TestJwts.rs256(keys.priv(), validUserClaims("42","USER").audience(List.of("other"))),
        TestJwts.rs256(keys.priv(), validUserClaims("42","USER").issuer("evil")),
        TestJwts.rs256(keys.priv(), validUserClaims("42","USER").claim("token_use", "service")),
        TestJwts.hs256("x".repeat(32), validUserClaims("42", "ADMIN")),          // algorithm confusion
        TestJwts.unsigned(validUserClaims("42", "ADMIN")),                       // alg none
        "not.a.jwt");
    bad.forEach(t -> assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtException.class));
}
// ServiceTokensTest  (SECRET = "s".repeat(32); MutableClock helper)
@Test void providerTokenPassesServiceDecoder() {
    var jwt = ServiceTokens.decoder(SECRET).decode(ServiceTokens.provider(SECRET, "booking-service", clock).token());
    assertThat(jwt.getSubject()).isEqualTo("booking-service");
    assertThat(jwt.getClaimAsString("role")).isEqualTo("SERVICE");
}
@Test void cachesUntilSixtySecondsBeforeExpiry() { /* t1; +3 min → same; +61 s → different */ }
@Test void serviceTokenIsRejectedByUserDecoder() {
    var t = ServiceTokens.provider(SECRET, "booking-service", Clock.systemUTC()).token();
    assertThatThrownBy(() -> UserTokens.decoder(keys.pub()).decode(t)).isInstanceOf(JwtException.class);
}
@Test void userTokenIsRejectedByServiceDecoder() {
    var t = TestJwts.rs256(keys.priv(), validUserClaims("42", "ADMIN"));
    assertThatThrownBy(() -> ServiceTokens.decoder(SECRET).decode(t)).isInstanceOf(JwtException.class);
}
@Test void hs256TokenWithUserRoleIsRejectedByServiceDecoder() {   // a service can't smuggle ADMIN into internal calls
    var t = TestJwts.hs256(SECRET, serviceClaims("booking-service").claim("role", "ADMIN"));
    assertThatThrownBy(() -> ServiceTokens.decoder(SECRET).decode(t)).isInstanceOf(JwtException.class);
}
@Test void rejectsShortSecret() {
    assertThatThrownBy(() -> ServiceTokens.decoder("short")).hasMessageContaining("at least 32 bytes");
}
// RoleAuthenticationConverterTest
@Test void mapsRoleAndSubject() { /* jwt(sub=42, role=ADMIN) → name "42", authorities ["ROLE_ADMIN"] */ }
// PemKeysTest
@Test void readsPemPair() { /* write TestKeys pair as PEM to @TempDir, read back, equal encoded bytes */ }
@Test void missingFileFailsWithLocation() {
    assertThatThrownBy(() -> PemKeys.publicKey(new FileSystemResource("nope.pem"))).hasMessageContaining("nope.pem");
}
// ProblemDetailsHandlerTest — @SpringBootTest(webEnvironment=MOCK) + @AutoConfigureMockMvc on a test app in package
// com.ticketrush.testapp (outside com.ticketrush.common) with a controller throwing ApiException(SEAT_LIMIT_EXCEEDED,"max 4")
// and another throwing new RuntimeException("db password leaked")
@Test void handlerIsRegisteredByAutoConfiguration() {
    mvc.perform(get("/boom")).andExpect(status().isConflict())
       .andExpect(jsonPath("$.code").value("SEAT_LIMIT_EXCEEDED")).andExpect(jsonPath("$.detail").value("max 4"));
}
@Test void unexpectedErrorsDoNotLeakMessages() {
    mvc.perform(get("/crash")).andExpect(status().isInternalServerError())
       .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
       .andExpect(content().string(not(containsString("leaked"))));
}
```

- [ ] **Step 2: Run** `./mvnw -pl common test` — Expected: compilation FAIL (classes missing).
- [ ] **Step 3: Implement per Interfaces.** `UuidV7`: 48-bit ms timestamp, 12-bit counter in `rand_a` (reset per ms, incremented within a ms), 62 random bits from a shared `SecureRandom`; guarded by a `ReentrantLock` (avoids virtual-thread pinning on Java 21). Validators: compose with `JwtValidators.createDefaultWithValidators(...)` plus `JwtClaimValidator`s for `aud`, `token_use`, `role`; skew via `JwtTimestampValidator(CLOCK_SKEW)`. Service-token signing uses `NimbusJwtEncoder.withSecretKey(...)` with `MacAlgorithm.HS256` in the JWS header.
- [ ] **Step 4: Run** `./mvnw -pl common test` — Expected: all PASS.
- [ ] **Step 5: Commit** — `git add common && git commit -m "feat(common): ids, errors, token verification rules, service tokens, event envelope"`

---

### Task 3: Docker Compose infrastructure

**Files:**
- Create: `docker-compose.yml`, `infra/mysql/init/01-schemas.sql`, `infra/verify-infra.sh`, `infra/verify-infra.ps1`, `infra/keys/generate-dev-keys.sh`, `infra/keys/generate-dev-keys.ps1`

**Interfaces:**
- Produces Compose services `mysql` (3306), `redis` (6379), `kafka` (internal listener `kafka:9092`, KRaft single combined node), each with a healthcheck. DB users `user_svc`→`user_db`, `event_svc`→`event_db`, `booking_svc`→`booking_db`, `payment_svc`→`payment_db` (passwords from `.env`). Dev key files `infra/keys/jwt-private.pem` (PKCS#8) and `infra/keys/jwt-public.pem` (X.509 SubjectPublicKeyInfo).

- [ ] **Step 1: Write the verification scripts (the test).** Both run every SQL check inside the container (`docker compose exec -T mysql mysql ...`), so host shells only orchestrate; each prints `PASS`/`FAIL` per check and exits non-zero on any failure:
  1. **Fixture as root:** `CREATE TABLE event_db.isolation_probe(id INT PRIMARY KEY); INSERT INTO event_db.isolation_probe VALUES (1);` (idempotent: `DROP TABLE IF EXISTS` first).
  2. **Root can read it:** `SELECT COUNT(*) FROM event_db.isolation_probe` as root → `1` (proves the table exists, so the next failure is about permissions).
  3. **`user_svc` is denied:** same `SELECT` as `user_svc` → must fail **and** stderr must contain `ERROR 1142` (`SELECT command denied`). A different error (e.g. 1146 "doesn't exist") counts as FAIL.
  4. **`user_svc` can't see the schema:** `SHOW DATABASES` as `user_svc` lists `user_db` and not `event_db`.
  5. **`user_svc` works in its own schema:** `CREATE TABLE user_db.rw_probe(id INT); DROP TABLE user_db.rw_probe;` → success.
  6. **`user_svc` can't create schemas:** `CREATE DATABASE rogue_db` → fails with `ERROR 1044`.
  7. Repeat checks 3 and 5 for `event_svc`/`booking_svc`/`payment_svc` against a neighbour schema (loop).
  8. Cleanup: root drops `event_db.isolation_probe`.
  9. `SELECT @@version, @@global.time_zone` → version starts with `8.0`, time zone `+00:00`.
  10. `docker compose exec -T redis redis-cli PING` → `PONG`.
  11. `docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list` → exit 0.
- [ ] **Step 2: Run** `bash infra/verify-infra.sh` (PowerShell: `.\infra\verify-infra.ps1`) — Expected: FAIL (no containers).
- [ ] **Step 3: Write Compose, init SQL and key scripts.** Images: `mysql:8.0`, `redis:7-alpine`, `apache/kafka:<pinned 4.x tag>`. MySQL command `--default-time-zone=+00:00`. Init SQL: 4 schemas (`utf8mb4`, `utf8mb4_0900_ai_ci`), 4 users, `GRANT ALL PRIVILEGES ON <schema>.* TO '<user>'@'%'` and nothing else. Memory limits: mysql 768m, kafka 1g, redis 128m. Key scripts: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem` then `openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem`; skip if files exist; the `.ps1` looks for `openssl` on PATH, then `C:\Program Files\Git\usr\bin\openssl.exe`, and prints install guidance if neither exists.
- [ ] **Step 4: Run** `docker compose up -d mysql redis kafka` then the verify script — Expected: `ALL INFRA CHECKS PASSED`. Run `bash infra/keys/generate-dev-keys.sh` — Expected: both PEM files exist; `openssl pkey -pubin -in infra/keys/jwt-public.pem -noout -text` prints `Public-Key: (2048 bit)`.
- [ ] **Step 5: Commit** — `git commit -m "infra: compose, per-service db users with isolation proof, dev keys"` (PEM files stay ignored).

---

### Task 4: Eureka discovery server

**Files:**
- Create: `discovery-server/pom.xml`, `discovery-server/src/main/java/com/ticketrush/discovery/DiscoveryServerApplication.java`, `discovery-server/src/main/resources/application.yml`, `discovery-server/Dockerfile`
- Modify: `pom.xml` (module), `docker-compose.yml` (service)
- Test: `discovery-server/src/test/java/com/ticketrush/discovery/DiscoveryServerTest.java`

**Interfaces:**
- Produces: Eureka at `http://discovery-server:8761/eureka/` (Compose) / `http://localhost:8761`; Actuator `health` exposed.
- Dependencies: `spring-cloud-starter-netflix-eureka-server`, `spring-boot-starter-actuator`; test `spring-boot-starter-webmvc-test`.

- [ ] **Step 1: Failing test** — `@SpringBootTest(webEnvironment = RANDOM_PORT)`; `GET /eureka/apps` with `Accept: application/json` → 200 and body contains `"applications"`; `GET /actuator/health` → `{"status":"UP"}`.
- [ ] **Step 2: Run** `./mvnw -pl discovery-server test` — Expected: FAIL.
- [ ] **Step 3: Implement** `@EnableEurekaServer`; `eureka.client.register-with-eureka=false`, `fetch-registry=false`; port 8761. Dockerfile pattern (all services): build stage `eclipse-temurin:21-jdk` copying the repo and running `./mvnw -pl <module> -am package -DskipTests`; run stage `eclipse-temurin:21-jre`, `JAVA_TOOL_OPTIONS="-Xmx256m -Duser.timezone=UTC"`, `curl` installed for healthchecks. Compose healthcheck `curl -f http://localhost:8761/actuator/health`.
- [ ] **Step 4: Run** test — PASS; `docker compose up -d --build discovery-server` → `healthy`.
- [ ] **Step 5: Commit** — `git commit -m "feat: eureka discovery server"`

---

### Task 5: user-service (register, login, me, admin seed)

**Files:**
- Create: files listed under `user-service/` in File Structure
- Modify: `pom.xml`, `docker-compose.yml`
- Test: `user-service/src/test/java/com/ticketrush/user/AuthFlowIT.java`, `user-service/src/test/java/com/ticketrush/user/FlywayAndHandlerIT.java`

**Dependencies:** `common`, `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-mysql`, `com.mysql:mysql-connector-j` (runtime), `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-actuator`, `spring-cloud-starter-netflix-eureka-client`, `springdoc-openapi-starter-webmvc-ui`. Test: `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, `spring-boot-testcontainers`, `testcontainers-mysql`, `testcontainers-junit-jupiter`.

**Interfaces:**
- Consumes: `UserTokens`, `RoleAuthenticationConverter`, `PemKeys`, `TokenClaims`, `ApiException`, `ErrorCode`, auto-registered `ProblemDetailsHandler` (Task 2).
- Produces (public, routed by gateway in Task 6):
  - `POST /api/users/register` `{email, password}` → `201 {id, email, role}`; duplicate → `409 EMAIL_TAKEN`; invalid → `400 VALIDATION_FAILED` with `errors`.
  - `POST /api/users/login` `{email, password}` → `200 {accessToken, tokenType:"Bearer", expiresIn:3600}`; wrong email, wrong password, or a password over 72 bytes → `401 INVALID_CREDENTIALS` (identical bodies).
  - `GET /api/users/me` (user token) → `200 {id, email, role}`; missing/invalid token → 401.
  - `/internal/**` → `denyAll` (user-service exposes no internal API).
  - `UserTokenIssuer.issue(User user): String` — RS256 user token with the claims in "Security decision" (`iss`, `aud`, `token_use=user`, `sub`, `role`, `iat`, `exp`=+1 h, `jti`=random UUID). **Only class in the repo that can sign user tokens.**
- `RegisterRequest(@NotBlank @Email @Size(max=255) String email, @NotBlank @Size(min=8,max=72) @MaxUtf8Bytes(72) String password)` — compact constructor applies `email == null ? null : email.strip().toLowerCase(Locale.ROOT)`, so normalization happens during binding, **before** Bean Validation and persistence. `@MaxUtf8Bytes` is a small constraint in user-service (`value.getBytes(UTF_8).length <= max`). `LoginRequest` normalizes the same way, with no size validation (login always answers 401 on bad input).
- Schema `V1__users.sql`: the `users` table from spec §6.1 (incl. `UNIQUE KEY uk_users_email`).
- Config: datasource `jdbc:mysql://mysql:3306/user_db?connectionTimeZone=UTC`, user `user_svc`; `spring.jpa.hibernate.ddl-auto=validate`; `ticketrush.jwt.private-key=file:${JWT_PRIVATE_KEY_FILE}`, `ticketrush.jwt.public-key=file:${JWT_PUBLIC_KEY_FILE}` (Compose mounts both read-only at `/run/keys/`); Eureka client; port 8081; virtual threads; Hikari max 10.

- [ ] **Step 1: Write failing integration tests.** Testcontainers `org.testcontainers.mysql.MySQLContainer("mysql:8.0")` with `withDatabaseName("user_db").withUsername("user_svc")` and `@ServiceConnection`; key pair generated in a `@TestConfiguration` and written to `@TempDir` PEM files referenced by `ticketrush.jwt.*` via `@DynamicPropertySource`; `eureka.client.enabled=false`; `ADMIN_EMAIL=admin@ticketrush.dev`, `ADMIN_PASSWORD=Admin#12345`; `@AutoConfigureMockMvc`.

```java
// AuthFlowIT
@Test void registerLoginMe() {
    register("alice@example.com", "Secret#123").andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER"));
    var token = loginToken("alice@example.com", "Secret#123");
    mvc.perform(get("/api/users/me").header(AUTHORIZATION, "Bearer " + token))
       .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("alice@example.com"));
}
@Test void emailIsNormalizedBeforeValidationAndStorage() {          // Review Focus 1
    register(" Alice@Example.COM ", "Secret#123").andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("alice@example.com"));
    register("alice@example.com", "Other#1234").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    assertThat(loginToken("\tALICE@example.com ", "Secret#123")).isNotBlank();
    assertThat(jdbc.queryForObject("SELECT email FROM users WHERE email LIKE '%alice%'", String.class)).isEqualTo("alice@example.com");
}
@Test void whitespaceOnlyEmailIsInvalid() {
    register("   ", "Secret#123").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());
}
@Test void concurrentDuplicateRegistrationsYieldExactlyOneAccount() {   // DB unique constraint, not check-then-insert
    var results = runConcurrently(10, () -> register("race@example.com", "Secret#123").andReturn().getResponse().getStatus());
    assertThat(results).containsOnly(201, 409);
    assertThat(results.stream().filter(s -> s == 201)).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='race@example.com'", Integer.class)).isEqualTo(1);
}
@ParameterizedTest @CsvSource({"7,400", "8,201", "72,201", "73,400"})   // ASCII length boundaries
void passwordCharacterBoundaries(int len, int status) { register(uniqueEmail(), "a".repeat(len)).andExpect(status().is(status)); }
@Test void multiByteBoundary() {                                          // Review Focus 3
    register(uniqueEmail(), "€".repeat(24)).andExpect(status().isCreated());      // 72 bytes
    register(uniqueEmail(), "€".repeat(25)).andExpect(status().isBadRequest())    // 25 chars but 75 bytes
        .andExpect(jsonPath("$.errors.password").exists());
}
@Test void overlongPasswordAtLoginIs401Not500() {
    login("alice@example.com", "€".repeat(40)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
}
@Test void wrongPasswordAndUnknownEmailLookIdentical() {
    var a = login("alice@example.com", "nope").andReturn().getResponse().getContentAsString();
    var b = login("ghost@example.com", "nope").andReturn().getResponse().getContentAsString();
    assertThat(a).isEqualTo(b).contains("INVALID_CREDENTIALS");
}
@Test void meRejectsMissingForgedAndServiceTokens() {
    mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/users/me").header(AUTHORIZATION, "Bearer " + TestJwts.rs256(TestKeys.rsa().priv(), validUserClaims("1","ADMIN"))))
       .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/users/me").header(AUTHORIZATION, "Bearer " + ServiceTokens.provider("s".repeat(32), "booking-service", Clock.systemUTC()).token()))
       .andExpect(status().isUnauthorized());
}
@Test void internalPathsAreDenied() { mvc.perform(get("/internal/anything").header(AUTHORIZATION, "Bearer " + loginToken(...))).andExpect(status().isForbidden()); }
@Test void issuedTokenHasContractClaims() {
    var jwt = UserTokens.decoder(publicKey).decode(loginToken("alice@example.com", "Secret#123"));
    assertThat(jwt.getIssuer().toString()).isEqualTo("ticketrush-user-service");
    assertThat(jwt.getAudience()).containsExactly("ticketrush-api");
    assertThat(jwt.getClaimAsString("token_use")).isEqualTo("user");
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
}
@Test void adminSeededOnceWithAdminRole() {
    assertThat(roleOf(loginToken("admin@ticketrush.dev", "Admin#12345"))).isEqualTo("ADMIN");
    adminSeeder.run(null);   // second run: no exception, still exactly one admin row
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='admin@ticketrush.dev'", Integer.class)).isEqualTo(1);
}
// FlywayAndHandlerIT
@Test void flywayAppliedV1AndHibernateValidates() {
    assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='1'", Boolean.class)).isTrue();
    assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");   // context started ⇒ entity matches schema
    assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("user_db");
}
@Test void sharedProblemDetailsHandlerIsActive() {
    assertThat(context.getBeansOfType(ProblemDetailsHandler.class)).hasSize(1);
    register("bad-email", "Secret#123").andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
}
@Test void missingKeyFileFailsStartupClearly() {                         // Review Focus 5
    var runner = new ApplicationContextRunner()/* user-service security config + ticketrush.jwt.private-key=file:/nope.pem */;
    runner.run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause().hasMessageContaining("/nope.pem"));
}
```

- [ ] **Step 2: Run** `./mvnw -pl user-service verify` — Expected: FAIL.
- [ ] **Step 3: Implement.** BCrypt via `BCryptPasswordEncoder(10)`; `AuthService.login` returns `INVALID_CREDENTIALS` without calling BCrypt when the password exceeds 72 bytes, and runs one `matches` against a fixed dummy hash for unknown emails (equal timing). Duplicates: save and catch `DataIntegrityViolationException` → `EMAIL_TAKEN` (no pre-check). `SecurityConfig`: stateless, CSRF off; `/internal/**` `denyAll`; `permitAll` for `POST /api/users/register`, `POST /api/users/login`, `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`; everything else authenticated; `oauth2ResourceServer(jwt -> jwt.decoder(UserTokens.decoder(publicKey)).jwtAuthenticationConverter(new RoleAuthenticationConverter()))`. `UserTokenIssuer` builds a `NimbusJwtEncoder` from the RSA key pair (`new RSAKey.Builder(pub).privateKey(priv).build()`). Keys loaded with `PemKeys` at startup (fail fast). `AdminSeeder` (`ApplicationRunner`) inserts the admin if absent and swallows only the duplicate-key race.
- [ ] **Step 4: Run** `./mvnw -pl user-service verify` — Expected: all PASS.
- [ ] **Step 5: Add to Compose and commit.** Service `user-service`: **no published port**; volumes `./infra/keys/jwt-private.pem:/run/keys/jwt-private.pem:ro` and the public key; env `JWT_PRIVATE_KEY_FILE=/run/keys/jwt-private.pem`, `JWT_PUBLIC_KEY_FILE=/run/keys/jwt-public.pem`; `depends_on` mysql + discovery-server `service_healthy`; healthcheck on `/actuator/health`. `git commit -m "feat(user): register/login/me, RS256 user tokens, admin seed"`

---

### Task 6: API gateway (routing, authorization, `/internal/**` deny, rate limits, CORS)

**Files:**
- Create: files listed under `api-gateway/` in File Structure
- Modify: `pom.xml`, `docker-compose.yml`
- Test: `api-gateway/src/test/java/com/ticketrush/gateway/GatewayIT.java`, `api-gateway/src/test/java/com/ticketrush/gateway/RateLimitKeyResolverTest.java`

**Dependencies:** `common`, `spring-cloud-starter-gateway-server-webflux`, `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-data-redis-reactive`, `spring-boot-starter-actuator`, `spring-cloud-starter-netflix-eureka-client`. **Not** `spring-boot-starter-webmvc` (Gateway WebFlux refuses to start with MVC on the classpath; `common` brings only `spring-web`). Test: `spring-boot-starter-webflux-test`, `spring-boot-starter-security-test`, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `mockwebserver3`.

**Interfaces:**
- Consumes: `UserTokens.validator()`, `RoleAuthenticationConverter` (adapted with `ReactiveJwtAuthenticationConverterAdapter`), `PemKeys.publicKey`, `ServiceTokens` (tests only).
- Holds **only the RSA public key** (`ticketrush.jwt.public-key=file:${JWT_PUBLIC_KEY_FILE}`); no private key, no internal secret. Decoder: `NimbusReactiveJwtDecoder.withPublicKey(key).signatureAlgorithm(RS256).build()` + `setJwtValidator(UserTokens.validator())`.
- **Authorization rules** (evaluated in this order):

| Matcher | Rule |
|---|---|
| `/internal/**` (any method) | `denyAll` |
| `/actuator/health` | `permitAll` |
| `POST /api/users/register`, `POST /api/users/login` | `permitAll` |
| `GET /api/events`, `GET /api/events/**` | `permitAll` |
| `POST`, `PUT`, `PATCH`, `DELETE` on `/api/events`, `/api/events/**` | `hasRole('ADMIN')` |
| `/api/assistant/admin/**` (any method) | `hasRole('ADMIN')` |
| `/api/users/**`, `/api/bookings/**`, `/api/payments/**`, `/api/assistant/**` | `authenticated` |
| anything else | `denyAll` |

  Downstream services repeat their own checks (event-service enforces ADMIN itself in plan 2) — the gateway is not the only line of defense.
- **Routes** (`lb://`; first match wins, so write-routes come before read-routes):

| Route id | Predicates | Rate limiter / key resolver |
|---|---|---|
| `bookings-write` | `Path=/api/bookings/**`, `Method=POST` | `strictLimiter` / `strictKeyResolver` |
| `payments-write` | `Path=/api/payments/**`, `Method=POST` | `strictLimiter` / `strictKeyResolver` |
| `bookings` | `Path=/api/bookings/**` | `defaultLimiter` / `defaultKeyResolver` |
| `payments` | `Path=/api/payments/**` | `defaultLimiter` / `defaultKeyResolver` |
| `events` | `Path=/api/events/**` | `defaultLimiter` / `defaultKeyResolver` |
| `users` | `Path=/api/users/**` | `defaultLimiter` / `defaultKeyResolver` |
| `assistant` | `Path=/api/assistant/**` → `lb://ai-assistant` | `assistantLimiter` / `assistantKeyResolver` |

  No route matches `/internal/**`.
- **Rate limiting:**
  - `strictLimiter` / `defaultLimiter` / `assistantLimiter`: `RedisRateLimiter(replenishRate, burstCapacity, requestedTokens)` from properties `ticketrush.ratelimit.strict.*` (prod 2, 5, 1), `ticketrush.ratelimit.default.*` (prod 20, 40, 1) and `ticketrush.ratelimit.assistant.*` (prod 1, 60, 6 — i.e. 10 requests/min with a burst of 10, using the `requestedTokens` trick because the replenish rate is per second).
  - `strictKeyResolver` returns `"strict:" + clientKey`, `defaultKeyResolver` returns `"default:" + clientKey`, `assistantKeyResolver` returns `"assistant:" + clientKey`. The separate prefixes keep the buckets apart, so the design doesn't depend on whether the limiter includes the route id in its Redis key.
  - `clientKey` = authenticated principal name (`sub`), else `exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()`.
  - `server.forward-headers-strategy=none`, so `X-Forwarded-For` never rewrites the remote address. If a trusted proxy is added later, use an explicit trusted-proxy allow-list (README note).
- **CORS:** origin `http://localhost:5173`; methods GET, POST, PUT, PATCH, DELETE, OPTIONS; headers `Authorization, Content-Type, Idempotency-Key`; exposed `X-RateLimit-Remaining, X-RateLimit-Replenish-Rate, X-RateLimit-Burst-Capacity`.

- [ ] **Step 1: Write failing tests.** `GatewayIT`: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureWebTestClient`; Redis via `GenericContainer("redis:7-alpine").withExposedPorts(6379)` with `@ServiceConnection(name = "redis")`; `MockWebServer` (always 200 `{}`) stands in for every downstream, with the test profile pointing all route URIs at it (`eureka.client.enabled=false`); key pair generated in-test, public key written to `@TempDir`. **Deterministic limiter settings in the test profile:** strict `replenishRate=1, burstCapacity=300, requestedTokens=60` (exactly 5 requests per burst, then 1 request/min); default `1, 180, 60` (exactly 3 per burst); assistant `1, 120, 60` (exactly 2 per burst). A test `@BeforeEach` runs `FLUSHALL` on Redis.

```java
@Test void publicReadsNeedNoToken() {
    client.get().uri("/api/events").exchange().expectStatus().isOk();
    client.get().uri("/api/events/1/seats").exchange().expectStatus().isOk();
}
@Test void eventWritesRequireAdmin() {
    client.post().uri("/api/events").exchange().expectStatus().isUnauthorized();
    client.post().uri("/api/events").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isForbidden();
    client.delete().uri("/api/events/1").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isForbidden();
    client.post().uri("/api/events").header(AUTHORIZATION, bearer(userToken("1", "ADMIN"))).exchange().expectStatus().isOk();
}
@Test void bookingsPaymentsAndMeRequireAuthentication() {
    for (var path : List.of("/api/bookings", "/api/payments", "/api/users/me"))
        client.get().uri(path).exchange().expectStatus().isUnauthorized();
    client.get().uri("/api/bookings/me").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isOk();
}
@Test void assistantRequiresAuthAndAdminForReindex() {
    client.post().uri("/api/assistant/chat").exchange().expectStatus().isUnauthorized();
    client.post().uri("/api/assistant/chat").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isOk();
    client.post().uri("/api/assistant/admin/reindex").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isForbidden();
    client.post().uri("/api/assistant/admin/reindex").header(AUTHORIZATION, bearer(userToken("1", "ADMIN"))).exchange().expectStatus().isOk();
}
@Test void assistantLimiterIsSeparateAndExact() {   // test profile: assistant 1, 120, 60 → exactly 2 per burst
    var codes = post("/api/assistant/chat", userToken("7", "USER"), 3);
    assertThat(codes).containsExactly(200, 200, 429);
    assertThat(post("/api/bookings", userToken("7", "USER"), 1)).containsOnly(200);   // other buckets unaffected
}
@Test void registerAndLoginArePublic() {
    client.post().uri("/api/users/register").exchange().expectStatus().isOk();
    client.post().uri("/api/users/login").exchange().expectStatus().isOk();
}
@Test void forgedAndWrongKindTokensAre401Not500() {               // Review Focus 2
    var bad = List.of("garbage", expiredUserToken(), userTokenSignedByOtherKey(), unsignedUserToken(),
                      TestJwts.hs256("x".repeat(32), validUserClaims("1", "ADMIN")),
                      ServiceTokens.provider("s".repeat(32), "booking-service", Clock.systemUTC()).token(),
                      userTokenWithAudience("other"));
    for (var t : bad) client.get().uri("/api/bookings/me").header(AUTHORIZATION, bearer(t)).exchange().expectStatus().isUnauthorized();
}
@Test void internalIsDeniedForEveryone() {
    client.get().uri("/internal/bookings/status").exchange().expectStatus().isUnauthorized();          // anonymous → 401
    client.get().uri("/internal/bookings/status").header(AUTHORIZATION, bearer(userToken("1", "ADMIN")))
          .exchange().expectStatus().isForbidden();                                                   // authenticated → 403
    assertThat(mockServer.getRequestCount()).isZero();                                                // never forwarded
}
@Test void unknownPathsAreDenied() { client.get().uri("/foo").header(AUTHORIZATION, bearer(userToken("7","USER"))).exchange().expectStatus().isForbidden(); }
@Test void strictLimiterAllowsExactlyFiveThen429() {
    var codes = post("/api/bookings", userToken("7", "USER"), 8);
    assertThat(codes.subList(0, 5)).containsOnly(200);
    assertThat(codes.subList(5, 8)).containsOnly(429);
}
@Test void strictAndDefaultBucketsAreSeparate() {
    post("/api/bookings", userToken("7", "USER"), 6);                                   // strict exhausted
    client.get().uri("/api/bookings/me").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isOk();
}
@Test void limitsArePerUser() {
    post("/api/bookings", userToken("7", "USER"), 6);
    assertThat(post("/api/bookings", userToken("8", "USER"), 1)).containsOnly(200);
}
@Test void spoofedForwardedForDoesNotResetAnonymousBucket() {      // Review Focus 4
    var codes = IntStream.range(0, 5).mapToObj(i -> client.get().uri("/api/events")
        .header("X-Forwarded-For", "203.0.113." + i).exchange().returnResult(Void.class).getStatus().value()).toList();
    assertThat(codes).containsExactly(200, 200, 200, 429, 429);  // default test burst = 3, same socket address
}
@Test void unauthenticatedWritesDoNotConsumeTokens() {
    IntStream.range(0, 10).forEach(i -> client.post().uri("/api/bookings").exchange().expectStatus().isUnauthorized());
    assertThat(post("/api/bookings", userToken("9", "USER"), 1)).containsOnly(200);
}
@Test void corsPreflightAllowsFrontend() {
    client.options().uri("/api/bookings").header("Origin", "http://localhost:5173")
        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,idempotency-key")
        .exchange().expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
}
@Test void gatewayCannotSign() {                                  // signing boundary
    assertThat(context.getBeanNamesForType(JwtEncoder.class)).isEmpty();
    assertThat(env.getProperty("INTERNAL_JWT_SECRET")).isNull();
}
```
`RateLimitKeyResolverTest`:
```java
@Test void anonymousKeyIgnoresForwardedHeader() {
    var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/events")
        .remoteAddress(new InetSocketAddress("10.0.0.5", 5000)).header("X-Forwarded-For", "1.2.3.4"));
    assertThat(defaultKeyResolver.resolve(ex).block()).isEqualTo("default:10.0.0.5");
}
@Test void authenticatedKeyIsSubjectWithPrefix() { /* exchange with principal JwtAuthenticationToken(sub=42) → "strict:42" from strictKeyResolver */ }
```

- [ ] **Step 2: Run** `./mvnw -pl api-gateway verify` — Expected: FAIL.
- [ ] **Step 3: Implement** `GatewaySecurityConfig` (`SecurityWebFilterChain` with the matcher table in order, stateless, CSRF off, `oauth2ResourceServer(jwt -> jwt.jwtDecoder(decoder).jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(new RoleAuthenticationConverter())))`, CORS source per Interfaces), `RateLimitConfig` (two limiter beans from properties, two key resolvers), routes in `application.yml` (`RequestRateLimiter` filter with `rate-limiter: "#{@strictLimiter}"` / `key-resolver: "#{@strictKeyResolver}"` etc.). Public key loaded with `PemKeys` at startup (fail fast). Port 8080, Eureka client on, `server.forward-headers-strategy=none`.
- [ ] **Step 4: Run** `./mvnw -pl api-gateway verify` — Expected: all PASS.
- [ ] **Step 5: Add to Compose and commit.** Service `api-gateway`: published `8080:8080`; mount **only** `jwt-public.pem` read-only; `depends_on` redis + discovery-server `service_healthy`. `git commit -m "feat(gateway): rs256 verification, route authorization, internal deny, deterministic rate limits, cors"`

---

### Task 7: Foundation end-to-end smoke

**Files:**
- Create: `infra/smoke/foundation-smoke.sh`, `infra/smoke/foundation-smoke.ps1`
- Modify: `README.md` (run instructions, version decision, security model)

**Interfaces:**
- Consumes everything above. Produces the roadmap's plan-1 "Done when" check.

- [ ] **Step 1: Write the smoke scripts.** `.sh` uses `curl` + `jq`. `.ps1` must run on Windows PowerShell 5.1, which lacks `-SkipHttpErrorCheck`, so it wraps `Invoke-WebRequest` in `try/catch` and reads the status from `$_.Exception.Response.StatusCode`. Both exit non-zero on any mismatch:
  1. Poll `GET http://localhost:8080/actuator/health` until `UP` (max 180 s).
  2. Poll `GET http://localhost:8761/eureka/apps/USER-SERVICE` (Accept JSON) until 200 (max 120 s).
  3. Register `smoke-<epoch>@example.com` with password `Smoke#12345` → 201.
  4. Login → `accessToken` present; decode the payload (base64url) → `iss=ticketrush-user-service`, `aud` contains `ticketrush-api`, `token_use=user`.
  5. `GET /api/users/me` with token → email matches.
  6. `GET /api/users/me` without token → 401.
  7. `GET /internal/anything` with the token → 403.
  8. `POST /api/events` with the user token → 403 (ADMIN-only rule active even before event-service exists).
  9. `GET http://localhost:8081/actuator/health` → connection refused (user-service port not published).
  10. `docker compose ps --format json` → no container with `RestartCount > 0` (Review Focus 5).
- [ ] **Step 2: Run from cold** — `docker compose down -v`, `bash infra/keys/generate-dev-keys.sh`, `docker compose up -d --build`, `bash infra/smoke/foundation-smoke.sh` (PowerShell: the `.ps1` twins) — Expected: `FOUNDATION SMOKE PASSED`.
- [ ] **Step 3: Write README** — prerequisites (Docker Desktop, JDK 21, openssl via Git for Windows), `cp .env.example .env` (PowerShell `Copy-Item .env.example .env`), key generation, `docker compose up -d --build`, smoke commands for both shells, Swagger URL (through the gateway is not routed; use `docker compose exec user-service curl localhost:8081/swagger-ui.html` or temporarily publish the port in a `docker-compose.override.yml`), the version decision table, and the token/security model table with its accepted trade-off.
- [ ] **Step 4: Commit** — `git commit -m "test: foundation smoke (sh + ps1) + README"`

---

## Revision v2 — change list

1. **Versions verified** on Maven Central / spring.io / springdoc / Boot 4 migration guide: Boot **4.1.2** + Cloud **2025.1.4** (2025.1.2+ supports Boot 4.1), Boot 4 starter names (`webmvc`, `security-oauth2-resource-server`, `flyway`, per-technology test starters), `resilience4j-spring-boot4` 2.4.0, springdoc 3.2.0, Testcontainers 2.0.5 (`testcontainers-mysql`, `org.testcontainers.mysql.MySQLContainer`), `mockwebserver3` 5.5.1. Probe extended to Spring Cloud; fallback mapping spelled out.
2. **JWT boundary:** user tokens moved from shared HS256 to **RS256**, signed only in user-service (`UserTokenIssuer`); gateway and services verify with the public key. Service tokens are a separate type (HS256 internal secret, own `iss`/`aud`/`token_use`/`role`), never given to gateway or user-service. Algorithm pinning, issuer, audience, expiry, `token_use` validation; cross-type rejection tests.
3. **Gateway authorization:** explicit matcher table — event writes ADMIN-only, only `GET /api/events/**` public, bookings/payments/users authenticated, `/internal/**` and unknown paths denied; tests for each.
4. **Handler discovery:** `ProblemDetailsHandler` registered by a servlet-only auto-configuration (not component scanning); tested in `common` from an outside package and in user-service.
5. **Email/BCrypt:** normalization in the request record's compact constructor (before validation and persistence); 72-**byte** limit via `@MaxUtf8Bytes`; boundary tests (7/8/72/73 ASCII, 24/25 `€`), login overlong → 401, concurrent duplicate registration → exactly one row.
6. **DB isolation test:** root-created fixture, root-read check, `user_svc` must fail with `ERROR 1142` specifically, schema visibility, own-schema DDL, no `CREATE DATABASE`, looped over all four users.
7. **Flyway:** `spring-boot-starter-flyway` + `flyway-mysql`; `ddl-auto=validate`; migration-history test; `SELECT DATABASE()` check.
8. **`common` dependencies:** compile-scope only (`spring-web`, `spring-security-oauth2-jose`, `spring-security-oauth2-resource-server`, `spring-boot-autoconfigure`); no MVC, Reactor, `provided` or `optional`; gateway builds its reactive decoder itself.
9. **Rate-limit tests:** configurable limiter properties; test profile uses `requestedTokens` so bursts are exact and refill is 1/min (no timing flakiness); separate `strict:`/`default:` key prefixes; per-user, bucket-separation, spoofed-XFF and unauthenticated-no-consume tests; `server.forward-headers-strategy=none`.
10. **Internal security:** documented three layers (no route + `denyAll`, unpublished ports, service-token filter chain with `hasRole('SERVICE')`); user-service denies `/internal/**`; smoke checks unpublished port.
11. Windows support: `.ps1` twins for every script; `mvnw.cmd` note.
12. Review Focus updated (forged/wrong-kind tokens, multi-byte passwords, missing keys).

## Revision v2.1 — AI Assistant hooks (spec §24)

Plan 1 owns the gateway, so the assistant's edge rules land here and plan 7 needs no gateway changes:
`/api/assistant/admin/**` ADMIN-only, `/api/assistant/**` authenticated, route `assistant` → `lb://ai-assistant`
with its own `assistantLimiter` (10/min/user) and `assistant:` key prefix, two new gateway tests, `ErrorCode.ASSISTANT_UNAVAILABLE`,
and an `ANTHROPIC_API_KEY` placeholder in `.env.example`. Spring AI (2.0.2, built against Boot 4.1.2) is **not** added in plan 1.

## Unresolved / deferred decisions

- **Exact `apache/kafka` image tag** — pick the newest 4.x tag at implementation time and pin it (no compatibility impact on plan 1; Kafka is only health-checked here).
- **Per-service service identities** (mTLS or per-service key pairs) — deferred; shared internal secret accepted and documented.
- **Swagger exposure** — services aren't published; README documents an override file for local browsing rather than routing `/v3/api-docs` through the gateway.
- **Resilience4j on Boot 4.1** — artifact is compiled against Boot 4.0.0; Task 1's probe proves auto-configuration on 4.1.2 before plan 3 relies on it.
