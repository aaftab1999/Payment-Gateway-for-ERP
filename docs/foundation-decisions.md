# Foundation Decisions

> Stage 2 documented decisions. This file records trade-offs made during the foundation setup phase for interview-defensibility and traceability.

## 1. Spring Boot parent POM (not BOM import)

**Decision**: Use `spring-boot-starter-parent` as the `<parent>`, not `<scope>import</scope>` of `spring-boot-dependencies`.

**Rationale**: Using the parent POM gives us:
- Automatic plugin version management (compiler, surefire, spring-boot-maven-plugin) → fewer warnings.
- Default Java version, resource handling, and repackage goal configuration.
- Starters resolve their own versions automatically.

**Alternative**: Using `spring-boot-dependencies` as a BOM import requires declaring every plugin version explicitly and managing starter versions manually — more boilerplate.

## 2. Kafka dependency: `spring-kafka`, not `spring-boot-starter-kafka`

**Decision**: Use `org.springframework.kafka:spring-kafka` directly instead of `spring-boot-starter-kafka`.

**Context**: During build, Maven Central returned 404 for `spring-boot-starter-kafka:3.3.3`. Investigation revealed that the `spring-boot-starter-kafka` artifact **only exists from Spring Boot 4.0+**. In Spring Boot 3.3.x, Kafka auto-configuration is triggered by having `spring-kafka` on the classpath, and `spring-boot-starter` (already present via `spring-boot-starter-web`) pulls in the needed infrastructure.

**Implication**: Kafka auto-configuration still works — Spring Boot's `KafkaAutoConfiguration` activates when `spring-kafka` is detected. The `@EnableKafka` and producer/consumer factory beans are auto-wired identically.

**Interview talking point**: *"Spring Boot starters are conveniences, not requirements. The auto-configuration is keyed on the underlying library artifact, not the starter wrapper."*

## 3. Virtual threads enabled

**Decision**: `-Dspring.threads.virtual.enabled=true` via `spring.threads.virtual.enabled` in `application.yml`.

**Rationale**: PostgreSQL calls, Redis round-trips, and provider HTTP are blocking I/O. Virtual threads let us handle thousands of concurrent payment requests with a small carrier-thread pool. This is the default recommendation for Spring Boot 3.x on Java 21.

**Trade-off**: Virtual threads increase CPU overhead under extreme contention (~5-10%). For a payment gateway whose throughput ceiling is external I/O, the trade is favorable.

## 4. Flyway for migrations (baseline only in Stage 2)

**Decision**: Flyway (not Liquibase).

**Why Flyway**:
- SQL is the lingua franca of database migrations — DBAs can review diffs without parsing XML/YAML.
- Simpler operational model: one row in `flyway_schema_history` per migration.
- Failed migration → transaction rolls back → schema stays in last-known-good state.
- `baseline-on-migrate: true` so the existing DB isn't reset.

**Migration versioning**: `V{version}___{description}.sql`. Spring Boot 3.3 auto-creates the schema history table on first run.

## 5. PostgreSQL: Tomcat JDBC connection pool (not Hikari)

**Decision**: Default (Tomcat pool) via `spring.datasource.hikari.*`.

**Note**: Spring Boot 3.3 defaults to Hikari (via `spring-boot-starter-jdbc`). The config uses `hikari.*` properties. Hikari is the industry standard for its speed and reliability.

## 6. Redis: cache-only pattern

**Decision**: Redis is used for idempotency **caching** and **health checks** in Stage 2. It is explicitly documented that Redis is **not** the source of truth for payment correctness.

**Rationale**:
- Redis is in-memory and ephemeral. If it restarts or loses data, correctness must not suffer.
- The authoritative idempotency records live in PostgreSQL.
- Redis provides a microsecond-latency fast-path for duplicate request detection.

**This is a deliberate, defensible architectural choice for interview discussion.**

## 7. Kafka: KRaft mode (no Zookeeper)

**Decision**: `quay.io/strimzi/kafka:latest-kraft` with `KAFKA_PROCESS_ROLS=broker`.

**Rationale**: Kafka 3.5+ supports KRaft (Kafka Raft) mode, eliminating the Zookeeper dependency. Simpler local setup; one fewer container to manage. This is the direction the Kafka community is moving.

**Known limitation**: Strimzi's `latest-kraft` tag may lag behind Confluent versions. For production we'd pin a specific version and use infrastructure-managed Kafka.

## 8. Internal health endpoint

**Decision**: `GET /internal/health` as a technical verification endpoint, separate from Actuator's `/actuator/health`.

**Rationale**:
- Provides a gateway-specific structured response (status, app name, correlation ID) that an external load balancer or Kubernetes liveness probe can parse.
- Does not expose Actuator details (which may include sensitive DB URLs) to unauthenticated callers.
- Demonstrates the correlation-ID propagation pipeline end-to-end.

**Trade-off**: Duplicates some functionality of `/actuator/health`. Accepted for educational clarity — students can see the difference between a "technical health" endpoint and Spring Boot's built-in health contributors.

## 9. Request logging filter

**Decision**: `RequestLoggingFilter` logs at INFO (method, URI, status, elapsed, headers) and DEBUG (scrubbed body).

**Security design**:
- Sensitive headers (`Authorization`, `X-Correlation-Id`, `Cookie`) are filtered.
- Sensitive JSON fields (`password`, `token`, `paymentToken`, `cardNumber`, `cvv`, etc.) are scrubbed with regex on body logging.
- Body logging is DEBUG-only (disabled in production by default).

**Trade-off**: Regex scrubbing on every DEBUG body is O(n) — acceptable because it's off by default.

## 10. No ERP stub in Stage 2

**Decision**: No ERP mock server is created in Stage 2. The architecture document states the ERP is external. ERP integration (callbacks, polling, event consumption) will be tested with test fixtures (e.g., WireMock) in Stage 3+.

**Rationale**: The brief explicitly forbids building an ERP; the mock exists only as an integration-test fixture, not a module.

## 11. Profiles

**Decision**: Two profiles — `local` (Docker Compose ports) and `test` (Testcontainers-compatible).

**Rationale**: `local` hardcodes localhost:15432, localhost:16379, localhost:19092. `test` uses Testcontainers `@ServiceConnection` to inject dynamic ports. This separation avoids environment-specific config in `application.yml` (shared defaults).

---

## What Stage 2 does NOT decide

These decisions are deferred to Stage 3+:

1. **Payment state machine** implementation (domain layer).
2. **Double-entry ledger** schema and posting logic.
3. **Idempotency cache key design** (merchant-scoped, payload hash).
4. **Kafka topic names** and producer transaction configuration.
5. **Outbox processor** (scheduled + transactional).
6. **Reconciliation job** scheduling and file format.
7. **Circuit breaker** strategy for provider HTTP calls.
