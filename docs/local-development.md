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
| Kafka | `quay.io/strimzi/kafka:latest-kraft` | `19092:9092` | none | Payment event streaming |

> Container ports are offset (15xxx, 16xxx, 19xxx) so they don't conflict with any existing local services.

## 2. Start the application

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
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
# Enter the Kafka container
docker exec -it pgw-kafka bash

# List topics
kafka-topics.sh --bootstrap-server localhost:19092 --list

# Create a topic (future stages)
kafka-topics.sh --bootstrap-server localhost:19092 \
  --create --topic payment.status --replication-factor 1 --partitions 6

# Consume events (future stages)
kafka-console-consumer.sh --bootstrap-server localhost:19092 \
  --topic payment.status --from-beginning
```

## 5. Run tests

### Unit / smoke tests (no Docker)

```bash
./mvnw test -Dspring.profiles.active=test
```

### Integration tests (Docker required)

```bash
# Full suite including Testcontainers
./mvnw verify

# Run a single integration test
./mvnw verify -Dtest=PostgresFlywayIntegrationTest

# Skip integration tests (run unit only)
./mvnw test
```

### Skipping tests with no Docker

```bash
./mvnw verify -Dmaven.test.skip=true
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

## Known limitations (Stage 2)

* Only one Flyway migration (`V1_0__baseline.sql`) exists — the full payment/ledger schema arrives in Stage 3.
* Kafka is configured but no topics are created automatically yet.
* The `/internal/health` endpoint is the only API endpoint.
* No business logic (payments, idempotency, ledger) exists yet.
