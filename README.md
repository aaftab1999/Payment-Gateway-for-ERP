# Payment Gateway & Settlement Core Engine

A production-oriented educational **payment gateway and settlement core engine** — a single Spring Boot monolith built to demonstrate production-grade financial engineering (double-entry ledger, idempotency, transactional outbox, Kafka events, reconciliation) **without** implementing the external ERP (invoice/billing UI is owned externally).

## Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.3.x |
| Web | Spring MVC + **virtual threads** |
| Database | PostgreSQL 16 (Flyway migrations) |
| Cache | Redis 7 (idempotency — cache-only, not source of truth) |
| Events | Apache Kafka (Strimzi KRaft) |
| Persistence | Spring Data JPA + JDBC |
| Observability | Micrometer (Prometheus), Actuator, SLF4J/Logback + MDC |
| Tests | JUnit 5 + Testcontainers + Awaitility |
| Build | Maven |

## Quick start

```bash
# 1. Start infrastructure
docker compose up -d

# 2. Run the application (local profile)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. Verify
curl http://localhost:8080/api/v1/internal/health
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/prometheus
```

> **Note on Spring Boot 3.3.x and Kafka**: `spring-boot-starter-kafka` does not exist as an artifact for Spring Boot 3.3.x. This project uses `spring-kafka` directly (version-managed by `spring-boot-dependencies`). See [Foundation Decisions](docs/foundation-decisions.md) for details.

## Project structure

```
src/
├── main/java/com/paymentgateway/settlement/
│   ├── PaymentGatewaySettlementApplication.java   # entry point
│   ├── api/controller/                             # REST controllers (health, later /charge)
│   ├── application/                                # use-case orchestration
│   ├── config/                                     # configuration properties
│   ├── domain/                                     # pure domain (Payment, Ledger, Money)
│   ├── infrastructure/                             # JPA, Kafka, Redis, HTTP clients
│   ├── observability/                              # MDC filter, request logger
│   └── common/                                     # shared exceptions/utilities
├── main/resources/
│   ├── application.yml                             # shared config
│   ├── application-local.yml                       # local dev (Docker Compose)
│   ├── application-test.yml                        # test defaults
│   └── db/migration/V1_0__baseline.sql             # Flyway migration
└── test/                                          # Testcontainers integration tests
```

## System boundary

| External ERP owns | Gateway owns |
|---|---|
| Customers, invoices, bills, outstanding balances, customer-facing UI | Payment records, payment state, idempotency, double-entry ledger, provider integration, payment events, settlement, reconciliation, observability |

## Documentation

* [Local Development Guide](docs/local-development.md)
* [Foundation Decisions](docs/foundation-decisions.md)
* [Full Architecture](docs/architecture.md)
