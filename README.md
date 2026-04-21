# Fraud Detection Platform

Production-style fraud detection platform built as an event-driven, multi-service Maven monorepo. The system ingests or generates transactions, enriches them with behavioral features, scores fraud risk, creates analyst alerts for high-risk cases, and exposes an internal React analyst console.

The repository intentionally does not contain third-party fraud datasets. Synthetic data is generated locally by Bash scripts or automatically by the Docker stack.

## Table Of Contents

- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Quick Start](#quick-start)
- [Synthetic Data](#synthetic-data)
- [Analyst Console](#analyst-console)
- [Services And Ports](#services-and-ports)
- [Kafka Topics](#kafka-topics)
- [API Surface](#api-surface)
- [Configuration](#configuration)
- [Reliability: Retry And DLT](#reliability-retry-and-dlt)
- [Logging And Correlation](#logging-and-correlation)
- [Idempotency And Performance](#idempotency-and-performance)
- [Security Hardening](#security-hardening)
- [Testing](#testing)
- [AI/ML Roadmap](#aiml-roadmap)
- [AI Analyst Assistant Roadmap](#ai-analyst-assistant-roadmap)
- [Project Structure](#project-structure)
- [Project Status](#project-status)

## Architecture

The platform is split into focused Spring Boot services connected through Kafka:

```text
REST ingest / simulator
  -> transactions.raw
  -> feature-enricher-service
  -> transactions.enriched
  -> fraud-scoring-service
  -> transactions.scored
  -> alert-service
  -> fraud.alerts / fraud.decisions
```

Each service owns one processing stage and publishes the next event only after its local responsibility is complete. Kafka contracts live in `common-events`; REST DTOs and persistence documents stay service-local.

Why separate services:

- `transaction-ingest-service` accepts external transaction submissions.
- `transaction-simulator-service` generates and replays synthetic traffic.
- `feature-enricher-service` computes derived fraud features.
- `fraud-scoring-service` evaluates fraud risk.
- `alert-service` persists scored transaction monitoring data, creates high-risk alerts, and records analyst decisions.
- `analyst-console-ui` provides the analyst workflow.

This split supports clearer ownership, independent scaling, failure isolation, and future ML/analyst workflow evolution. The tradeoff is higher operational complexity, which is intentional for this project.

## Technology Stack

- Java 21
- Spring Boot 3.x
- Maven
- Apache Kafka in KRaft mode
- MongoDB
- Redis
- Docker Compose
- React
- Vite
- Nginx for serving the built UI in Docker

## Quick Start

Prerequisites:

- Docker Desktop or compatible Docker Engine with Compose support.
- Java 21 and Maven only if running tests or services outside Docker.
- Node.js only for local frontend development outside Docker.

Start the full stack:

```bash
docker compose -f deployment/docker-compose.yml up --build
```

Detached mode:

```bash
docker compose -f deployment/docker-compose.yml up --build -d
```

Stop the stack:

```bash
docker compose -f deployment/docker-compose.yml down
```

Stop and remove volumes:

```bash
docker compose -f deployment/docker-compose.yml down -v
```

After startup, open:

```text
http://localhost:4173
```

The Docker stack automatically starts a synthetic replay bootstrap. Wait about 20-30 seconds after startup and refresh the UI if the first page still shows zero records.

## Synthetic Data

Public datasets such as Kaggle files should stay local and outside public Git history. The supported default data sources are platform-owned synthetic generators.

The generated target scenario mix is:

- `LOW`: 80%
- `MEDIUM`: 10% new-device plus 1% country-mismatch review cases
- `HIGH`: 7%
- `CRITICAL`: 2%

`LOW` and `MEDIUM` transactions are visible in the scored transaction monitor. Only `HIGH` and `CRITICAL` transactions create analyst alerts.

### Automatic Docker Data Bootstrap

`transaction-simulator-service` starts automatic replay in Docker through these compose values:

```yaml
AUTO_REPLAY_ENABLED: "true"
AUTO_REPLAY_SOURCE_TYPE: SYNTHETIC
AUTO_REPLAY_MAX_EVENTS: "300"
AUTO_REPLAY_THROTTLE_MILLIS: "5"
AUTO_REPLAY_START_DELAY_MILLIS: "15000"
```

This publishes 300 generated transactions after a short delay. The current generator uses a deterministic 100-event cycle: 80 normal, 10 medium new-device, 7 high-risk, 1 country-mismatch, and 2 critical account-takeover events.

### Bash Data Scripts

Run scripts from Git Bash, WSL, Linux, or macOS.

Generate dimensions:

```bash
bash scripts/generate-dimensions.sh
```

Generate canonical `TransactionRawEvent` JSONL:

```bash
bash scripts/generate-canonical-replay.sh --count 10000
```

Generate replay data plus CSV labels:

```bash
bash scripts/generate-labelled-dataset.sh --count 10000
```

Override distribution:

```bash
bash scripts/generate-labelled-dataset.sh \
  --count 10000 \
  --normal-percentage 80 \
  --new-device-percentage 10 \
  --high-proxy-percentage 7 \
  --country-mismatch-percentage 1 \
  --account-takeover-percentage 2
```

Generate durable replay JSONL:

```bash
bash scripts/generate-synthetic-replay.sh --count 10000
```

Replay generated JSONL:

```bash
bash scripts/start-synthetic-replay.sh --source-type JSONL --max-events 2000 --throttle-millis 10
```

Replay generated batch and create it if missing:

```bash
bash scripts/replay-generated-batch.sh --generate-if-missing --max-events 2000
```

Start in-service synthetic replay manually:

```bash
bash scripts/start-synthetic-replay.sh --source-type SYNTHETIC --max-events 2000 --throttle-millis 10
```

## Analyst Console

URL:

```text
http://localhost:4173
```

The UI contains:

- scored transaction monitor with `LOW`, `MEDIUM`, `HIGH`, and `CRITICAL` transactions
- pagination for browsing all scored transactions
- legitimate vs suspicious classification
- alert review queue for `HIGH` and `CRITICAL` cases
- alert details page
- analyst decision form

For local frontend development:

```bash
cd analyst-console-ui
npm install
npm run dev
```

The Vite dev server proxies `/api` requests to `alert-service` on `http://localhost:8085`.

## Services And Ports

- `transaction-ingest-service`: `8081`
- `transaction-simulator-service`: `8082`
- `feature-enricher-service`: `8083`
- `fraud-scoring-service`: `8084`
- `alert-service`: `8085`
- `analyst-console-ui`: `4173`
- Kafka: `9092`
- MongoDB: `27017`
- Redis: `6379`

Health endpoints:

- `http://localhost:8081/actuator/health`
- `http://localhost:8082/actuator/health`
- `http://localhost:8083/actuator/health`
- `http://localhost:8084/actuator/health`
- `http://localhost:8085/actuator/health`

All backend services also expose liveness/readiness health groups and `/actuator/info`.

## Kafka Topics

Topics are initialized by `deployment/init-kafka-topics.sh` through the one-off `kafka-topics-init` compose service.

- `transactions.raw`: raw transaction events from ingest and simulator to feature enrichment.
- `transactions.enriched`: enriched feature events from enricher to scoring.
- `transactions.scored`: scored risk events from scoring to alert service.
- `fraud.alerts`: alert events emitted by alert service.
- `fraud.decisions`: analyst decision events emitted by alert service.
- `transactions.dead-letter`: failed records after retries are exhausted.

## API Surface

Main local endpoints:

- `POST http://localhost:8081/api/v1/transactions`: ingest a transaction.
- `POST http://localhost:8082/api/v1/replay/start`: start replay.
- `POST http://localhost:8082/api/v1/replay/stop`: stop replay.
- `GET http://localhost:8082/api/v1/replay/status`: replay status.
- `GET http://localhost:8085/api/v1/transactions/scored?page=0&size=25`: paged scored transaction monitor data.
- `GET http://localhost:8085/api/v1/alerts`: alert queue.
- `GET http://localhost:8085/api/v1/alerts/{alertId}`: alert details.
- `POST http://localhost:8085/api/v1/alerts/{alertId}/decision`: submit analyst decision.

`/api/v1/transactions/scored` returns:

- `content`
- `totalElements`
- `totalPages`
- `page`
- `size`

Page size is bounded to `1..100`.

## Configuration

The platform externalizes environment-specific values and validates critical properties at startup.

Validated configuration groups include:

- Kafka topic names
- Kafka consumer retry settings
- replay source settings
- auto replay settings
- feature store windows and TTL values
- scoring thresholds and scoring mode

Expected runtime inputs:

- `KAFKA_BOOTSTRAP_SERVERS`
- `REDIS_HOST`
- `REDIS_PORT`
- `MONGODB_URI`
- `AUTO_REPLAY_ENABLED`
- optional replay dataset paths for `transaction-simulator-service`

Local defaults are provided for development, but invalid overrides fail fast.

### Fraud Scoring Modes

`fraud-scoring-service` supports:

- `RULE_BASED`: default final scoring path.
- `ML`: intended ML final path, with rule-based fallback if unavailable.
- `SHADOW`: rule-based final decision with ML diagnostics attached.
- `COMPARE`: rule-based final decision with rule-vs-ML comparison diagnostics.

The current ML adapter is a placeholder, so `ML` falls back to rule-based scoring until a model runtime is configured.

## Reliability: Retry And DLT

Kafka consumer reliability is configured consistently in:

- `feature-enricher-service`
- `fraud-scoring-service`
- `alert-service`

Current defaults:

- retry attempts: `3`
- retry backoff: `1000 ms`
- dead-letter topic: `transactions.dead-letter`

Externalized properties:

- `app.kafka.consumer.retry-attempts`
- `app.kafka.consumer.retry-backoff-millis`

Failure behavior:

1. Consumer receives a record.
2. Spring Kafka retries with fixed backoff.
3. Retry attempts are logged with service, topic, partition, offset, key, and delivery attempt.
4. Exhausted records are routed to `transactions.dead-letter`.

DLT routing uses Spring Kafka `DeadLetterPublishingRecoverer`, preserving original topic, partition, offset, exception class, and exception message in headers. Business services do not implement manual retry loops.

Operational notes:

- DLT records indicate processing defects, data defects, or dependency failures.
- DLT should be monitored before high-volume replay.
- A future production workflow should provide DLT inspection and controlled replay tooling.

## Logging And Correlation

`correlationId` is the primary cross-service identifier.

REST ingest behavior:

- `transaction-ingest-service` accepts optional `X-Correlation-Id`.
- If present, it is reused.
- If absent, a new id is generated.
- The final value is returned in `X-Correlation-Id`.
- The same value is stored in `TransactionRawEvent.correlationId`.

Kafka propagation:

- every event carries `correlationId`
- producers also add operational headers such as `correlationId`, `transactionId`, `traceId`, and `alertId`

Structured logs include:

- `transactionId`
- `correlationId`
- `alertId` where applicable
- `topic` where applicable
- retry `partition`, `offset`, and `deliveryAttempt`

Correlation currently covers REST ingest, raw publication, enriched publication, scored publication, fraud alert publication, and fraud decision publication.

Future hardening could introduce a shared observability module for Kafka header constants, MDC utilities, and interceptors.

## Idempotency And Performance

Kafka consumers may receive the same business event more than once.

Current guarantees:

- `transaction-ingest-service` publishes raw events keyed by `transactionId`.
- `feature-enricher-service` checks Redis before loading feature windows and skips already processed customer-scoped transaction ids.
- Redis feature windows use sorted sets keyed by customer and merchant.
- Redis TTLs are applied to transaction, merchant, known-device, processed-transaction, and last-transaction keys.
- `fraud-scoring-service` remains stateless.
- `alert-service` prevents duplicate alert documents with a unique `transactionId` index and treats duplicate-key races as benign duplicate outcomes.

Performance considerations:

- Feature enrichment is the main hot path.
- Duplicate raw events are short-circuited before expensive Redis reads.
- Redis transaction windows are pruned by score during snapshot loading.
- Kafka listeners remain thin and delegate to services.
- MongoDB stores scored transaction monitoring data, alert-worthy cases, and analyst decisions.

Known tradeoffs:

- Redis idempotency is scoped by `customerId` and `transactionId`.
- The enricher publishes before recording feature state, avoiding false processed markers before Kafka publish succeeds.
- A failure after publish but before Redis record can still create duplicate enriched events; alert idempotency protects persisted alert state.
- High-volume production usage should consider pre-aggregated rolling counters or Redis Lua scripts for feature sums.

## Security Hardening

REST DTOs are validated at API boundaries with Jakarta Validation.

Validation covers:

- required identifiers and request fields
- bounded string lengths
- bounded collection and map sizes
- identifier formats
- ISO-like country and currency formats
- controlled uppercase codes for transaction type, authorization method, source system, channels, and related fields

API error responses are normalized:

- validation failures return stable field-level details
- malformed JSON returns `Malformed JSON request.`
- unexpected alert-service failures return `An unexpected error occurred.`
- stack traces and framework internals are not exposed

Kafka deserialization is restricted to platform event packages:

- `com.frauddetection.common.events.contract`
- `com.frauddetection.common.events.model`
- `com.frauddetection.common.events.enums`

Current assumptions:

- service-to-service traffic is internal
- DTOs remain separate from Kafka contracts and persistence documents
- future production-facing deployments should add authentication, authorization, rate limiting, gateway request-size limits, and transport controls such as mTLS

## Testing

Run backend tests:

```bash
mvn -pl transaction-ingest-service,transaction-simulator-service,feature-enricher-service,fraud-scoring-service,alert-service -am test
```

On Windows with repo-local Maven cache:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl transaction-ingest-service,transaction-simulator-service,feature-enricher-service,fraud-scoring-service,alert-service -am test
```

Run frontend build:

```bash
cd analyst-console-ui
npm run build
```

Testing layers:

- unit tests cover feature calculation, scoring, duplicate handling, alert creation, and replay behavior
- MVC slice tests cover validation, request mapping, and safe error responses
- Testcontainers integration tests cover Kafka, Redis, and MongoDB when Docker is available
- end-to-end tests validate raw ingestion through alert persistence and alert API access

Integration tests are skipped automatically when Docker/Testcontainers is unavailable.

## AI/ML Roadmap

Rule-based scoring remains the default production-safe path while the platform keeps stable seams for future ML inference.

Current scoring flow:

1. `feature-enricher-service` publishes `TransactionEnrichedEvent`.
2. `fraud-scoring-service` converts it into `FraudScoringRequest`.
3. `FraudScoringEngine` scores the request.
4. `TransactionScoredEventMapper` publishes `TransactionScoredEvent` with score, risk level, model metadata, reason codes, score details, feature snapshot, and alert recommendation.

Stable contracts:

- `FraudScoringRequest`: model-input boundary.
- `FraudScoreResult`: model-output boundary containing model name, version, inference timestamp, feature snapshot, explanation metadata, score details, and reason codes.

Future plug-in point:

```java
@Component
public class MlFraudScoringEngine implements FraudScoringEngine {
    @Override
    public FraudScoreResult score(FraudScoringRequest request) {
        // Transform request.featureSnapshot() into model features.
        // Call local model runtime or remote inference endpoint.
        // Return FraudScoreResult with model metadata and explanations.
    }
}
```

Explanation strategy:

- rule-based explanations use weighted reason codes
- ML explanations should populate `explanationMetadata` with methods such as `SHAP`, `FEATURE_IMPORTANCE`, or `MODEL_NATIVE_REASON_CODES`
- detailed explanation values should live in `scoreDetails`

Deployment guidance:

- keep model inference outside Kafka listeners
- keep model metadata explicit for auditability and rollback
- preserve `TransactionScoredEvent` compatibility
- add canary/shadow mode before replacing rule-based scoring

## AI Analyst Assistant Roadmap

The future AI analyst assistant should help analysts understand cases faster, but it must not own fraud decisions or bypass the alert workflow.

Current preparation exists under:

```text
com.frauddetection.alert.assistant
```

Main contract:

- `AnalystCaseSummaryUseCase`
- `AnalystCaseSummaryRequest`
- `AnalystCaseSummaryResponse`

The response is designed around:

- `transactionSummary`
- `mainFraudReasons`
- `customerRecentBehaviorSummary`
- `recommendedNextAction`
- `supportingEvidence`

Recommended implementation path:

1. Keep `alert-service` as owner of alert/case data.
2. Implement deterministic summaries first.
3. Add an optional LLM adapter behind a port such as `CaseNarrativeGenerator`.
4. Keep analyst decisions flowing through `/api/v1/alerts/{alertId}/decision`.
5. Emit decisions as `FraudDecisionEvent`.

Guardrails:

- recommend next actions, do not submit decisions automatically
- do not mutate alert state
- expose evidence and uncertainty
- sanitize DTOs before any LLM call
- audit model name, model version, template version, timestamp, and correlation id

## Project Structure

```text
common-events/                  Shared Kafka contracts, enums, and value objects
common-test-support/            Shared test fixtures and Testcontainers helpers
transaction-ingest-service/      External REST transaction ingestion
transaction-simulator-service/   Synthetic replay and generated traffic
feature-enricher-service/        Redis-backed feature enrichment
fraud-scoring-service/           Rule-based and future ML scoring
alert-service/                   Scored transaction projection, alerts, analyst decisions
analyst-console-ui/              React analyst console
deployment/                      Docker Compose and Dockerfiles
scripts/                         Bash synthetic dataset and replay scripts
```

## Project Status

The repository currently includes:

- full event-driven backend flow
- Docker Compose local stack
- automatic synthetic data bootstrap
- Bash synthetic dataset generators
- React analyst console with pagination
- scored transaction monitor and alert queue
- validation and security hardening
- retry and dead-letter handling
- structured logging and correlation propagation
- idempotency safeguards
- rule-based scoring with ML extension contracts
- future AI analyst assistant contracts

## 👤 Maintainer

**Milosz Podsiadly**  
📧 [m.podsiadly99@gmail.com](mailto:m.podsiadly99@gmail.com)  
🔗 [GitHub – MiloszPodsiadly](https://github.com/MiloszPodsiadly)

---

## 🪪 License

Licensed under the [MIT License](https://opensource.org/licenses/MIT).
