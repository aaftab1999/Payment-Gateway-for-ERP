# Local Development Guide

## Prerequisites

* Java 21 (JDK)
* Maven 3.8+
* Docker / Docker Compose
* At least 4 GB RAM available for containers

## 1. Start infrastructure

```bash
docker compose up -d
```

| Service | Image | Port | Credentials | Purpose |
|---|---|---|---|---|
| PostgreSQL | `postgres:16-alpine` | `15432:5432` | user: `pgs`, db: `pgs`, pass: `pgs_secret` | Financial source of truth |
| Redis | `redis:7-alpine` | `16379:6379` | none (no auth in dev) | Idempotency cache (not source of truth) |
| Kafka | `apache/kafka:3.8.1` | `19092:9092` | none | Payment event streaming |
| Kafka topic init | `apache/kafka:3.8.1` | internal | none | Creates `payment.events` and `payment.events.DLQ` |

> Container ports are offset (15xxx, 16xxx, 19xxx) so they don't conflict with any existing local services.

## 2. Start the application

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

* API: `http://localhost:8080/api/v1/`
* Actuator: `http://localhost:8081/actuator/`
* Prometheus metrics: `http://localhost:8081/actuator/prometheus`

## 3. Stop & clean

```bash
# Stop containers and remove data volumes
docker compose down -v

# Stop just containers
docker compose down
```

## 4. Inspect services

### PostgreSQL

```bash
psql -h localhost -p 15432 -U pgs -d pgs
-- password: pgs_secret

-- List tables after Flyway runs
\dt public.*
```

### Redis

```bash
redis-cli -p 16379 ping
redis-cli -p 16379 KEYS '*'
```

### Kafka

```bash
# Start Kafka and run the one-shot topic initializer
docker compose up -d kafka kafka-init

# Enter the Kafka container
docker exec -it pgw-kafka bash

# List topics using the internal listener
kafka-topics.sh --bootstrap-server localhost:29092 --list

# Inspect the payment topic
kafka-topics.sh --bootstrap-server localhost:29092 \
  --describe --topic payment.events

# Consume events
kafka-console-consumer.sh --bootstrap-server localhost:29092 \
  --topic payment.events --from-beginning
```

## 5. Run tests

### Unit / smoke tests (no Docker)

```bash
mvn test -Dspring.profiles.active=test
```

### Integration tests (Docker required)

```bash
# Full suite including Testcontainers
mvn verify

# Transactional outbox
mvn test -Dtest=PaymentOutboxTransactionIntegrationTest

# Publisher retries
mvn test -Dtest=OutboxPublisherRetryIntegrationTest

# Kafka publishing and ERP fixture
mvn test -Dtest=KafkaOutboxIntegrationTest

# Skip Docker-backed tests when Docker is unavailable
mvn test -Dtest='!*IntegrationTest'
```

Stage 5 uses Testcontainers with dynamically allocated PostgreSQL and Kafka ports. Do not hard-code host port `15432` for Kafka tests.


### Skipping tests with no Docker

```bash
mvn verify -Dmaven.test.skip=true
```

## Configuration precedence

Spring Boot profile precedence (highest wins):

| Order | Source |
|---|---|
| 1 | Command-line args |
| 2 | `SPRING_...` environment variables |
| 3 | `application-local.yml` / `application-test.yml` |
| 4 | `application.yml` |
| 5 | Compiled defaults |

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `localhost:15432/pgs` | Postgres JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `pgs` | Postgres user |
| `SPRING_DATASOURCE_PASSWORD` | `pgs_secret` | Postgres password |
| `SPRING_REDIS_HOST` | `localhost` | Redis host |
| `SPRING_REDIS_PORT` | `localhost` | Redis port |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092` | Kafka broker |
| `APP_ENV` | `local` | `local` / `test` / `prod` |
| `APP_CORRELATION_ID_HEADER_NAME` | `X-Correlation-Id` | Correlation header name |

## Current implementation notes

* Flyway migrations create the payment, idempotency, and transactional outbox schemas.
* Kafka topics are created by the `kafka-init` service.
* Payment and internal health APIs are available; settlement, refunds, chargebacks, and ledger processing remain out of scope.
* Stage 5 delivery is at-least-once; ERP consumers must deduplicate by event ID.
* Stage 5 is a handoff, not a green baseline: replay exception/`ChargeResult` contract alignment and integration verification remain pending. See [Stage 5 handoff](stage-5-outbox-kafka-erp.md#12-stage-5-handoff).
