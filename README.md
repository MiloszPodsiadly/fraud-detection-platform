# Fraud Detection Platform

Event-driven fraud detection platform built as a multi-service Maven monorepo with a React analyst console. The system ingests or generates transactions, enriches them with behavioral features, scores fraud risk, creates alerts and fraud cases, and supports analyst review workflows with RBAC and audit logging.

The repository intentionally uses platform-owned synthetic data generators. Third-party fraud datasets should stay local and outside public Git history.

## Table Of Contents

- [Overview](#overview)
- [Architecture At A Glance](#architecture-at-a-glance)
- [Core Capabilities](#core-capabilities)
- [Security Foundation v1](#security-foundation-v1)
- [Authorization Model RBAC](#authorization-model-rbac)
- [Audit Logging](#audit-logging)
- [Local Development](#local-development)
- [Frontend Analyst Console](#frontend-analyst-console)
- [Path To Production](#path-to-production)
- [Services And Ports](#services-and-ports)
- [Kafka Topics](#kafka-topics)
- [API Surface](#api-surface)
- [Configuration](#configuration)
- [Reliability Retry And DLT](#reliability-retry-and-dlt)
- [Logging And Correlation](#logging-and-correlation)
- [Idempotency And Performance](#idempotency-and-performance)
- [Testing](#testing)
- [ML Inference Service](#ml-inference-service)
- [AI Analyst Assistant](#ai-analyst-assistant)
- [Project Structure](#project-structure)
- [Documentation Index](#documentation-index)
- [Project Status](#project-status)

## Overview

This project models a production-style fraud detection workflow:

- transaction ingestion and replay
- behavioral feature enrichment
- rule-based and ML-assisted risk scoring
- alert creation for high-risk transactions
- fraud case management for grouped suspicious behavior
- analyst-facing review UI
- RBAC, security error handling, and audit logging for analyst actions

The goal is not to be a minimal demo. The repository shows service boundaries, event contracts, operational tradeoffs, security foundation work, and migration points toward production authentication.

## Architecture At A Glance

Processing flow:

```text
ingest / simulator
  -> transactions.raw
  -> feature-enricher-service
  -> transactions.enriched
  -> fraud-scoring-service
  -> transactions.scored
  -> alert-service
  -> alerts, fraud cases, analyst decisions
  -> analyst-console-ui
```

Component responsibilities:

| Component | Responsibility |
| --- | --- |
| `transaction-ingest-service` | REST API for external transaction submissions. |
| `transaction-simulator-service` | Synthetic replay and generated traffic for local runs. |
| `feature-enricher-service` | Redis-backed feature windows and derived fraud signals. |
| `fraud-scoring-service` | Rule-based scoring plus ML integration modes. |
| `ml-inference-service` | Python model runtime used by scoring in SHADOW/ML/COMPARE modes. |
| `alert-service` | Scored transaction projection, alert queue, fraud cases, analyst decisions, security, audit. |
| `analyst-console-ui` | React analyst console for monitoring, alert review, case updates, and security UX. |
| `common-events` | Shared Kafka event contracts, enums, and value objects. |
| `common-test-support` | Shared fixtures and Testcontainers helpers. |

Kafka contracts live in `common-events`. REST DTOs and persistence documents stay service-local.

## Core Capabilities

- Event-driven processing: services communicate through Kafka topics and publish the next event only after local responsibility is complete.
- Synthetic fraud data: local generators and Docker bootstrap produce deterministic traffic with rare high-risk scenarios.
- Feature enrichment: Redis-backed windows capture recent customer, merchant, device, and geo behavior.
- Scoring modes:
  - `RULE_BASED`: default deterministic scoring path.
  - `ML`: Python ML model is final scorer, with rule fallback if unavailable.
  - `SHADOW`: rule-based result remains final while ML diagnostics are attached.
  - `COMPARE`: rule-based result remains final while rule-vs-ML comparison diagnostics are attached.
- Alerting: `HIGH` and `CRITICAL` scored transactions create analyst alerts.
- Case management: rapid-transfer grouped fraud cases are created for `RAPID_TRANSFER_BURST_20K_PLN`.
- Analyst console: scored transaction monitor, alert queue, alert details, assistant summary, decision form, and fraud case update flow.
- Audit logging: analyst write actions emit structured audit events.

## Security Foundation v1

Security Foundation v1 protects the analyst workflow owned by `alert-service` and consumed by `analyst-console-ui`.

Implemented:

- Authentication skeleton for local/dev demo auth.
- RBAC with `AnalystRole` personas and `AnalystAuthority` enforcement strings.
- Endpoint protection for analyst APIs under `alert-service` `/api/v1/**`.
- Stable JSON HTTP 401/403 responses.
- Principal-based actor identity for analyst write paths.
- Audit logging v1 for alert decisions and fraud case updates.
- Frontend session awareness, role-aware action disabling, and dedicated 401/403 states.
- JWT/OIDC migration extension points.

Important security boundaries:

- Demo auth is local/dev only. It is disabled by default and controlled by `app.security.demo-auth.enabled`.
- Demo auth requires an allowed profile: `local`, `dev`, `docker-local`, or `test`.
- If demo auth is enabled outside those profiles, `alert-service` rejects startup.
- Demo auth is not the production authentication path.
- Production authentication should use JWT/OIDC Resource Server support and map external claims into the same internal authority model.
- Backend authorization is authoritative. Frontend gating is UX only.
- For secured write requests, actor identity comes from the authenticated principal, not from request payload `analystId`.

Main docs:

- [Security Foundation v1](docs/security-foundation-v1.md)

## Authorization Model RBAC

Roles describe analyst personas. Authorities are the enforcement contract.

Roles:

| Role | Intent |
| --- | --- |
| `READ_ONLY_ANALYST` | Can inspect queues and evidence without write actions. |
| `ANALYST` | Can review alerts and submit alert decisions. |
| `REVIEWER` | Can submit alert decisions and update fraud cases. |
| `FRAUD_OPS_ADMIN` | Has all Security Foundation v1 analyst workflow authorities. |

Example authorities:

- `alert:read`
- `assistant-summary:read`
- `alert:decision:submit`
- `fraud-case:read`
- `fraud-case:update`
- `transaction-monitor:read`

Representative endpoint matrix:

| Endpoint | Required authority |
| --- | --- |
| `GET /api/v1/alerts` | `alert:read` |
| `GET /api/v1/alerts/{alertId}` | `alert:read` |
| `GET /api/v1/alerts/{alertId}/assistant-summary` | `assistant-summary:read` |
| `POST /api/v1/alerts/{alertId}/decision` | `alert:decision:submit` |
| `GET /api/v1/fraud-cases` | `fraud-case:read` |
| `GET /api/v1/fraud-cases/{caseId}` | `fraud-case:read` |
| `PATCH /api/v1/fraud-cases/{caseId}` | `fraud-case:update` |
| `GET /api/v1/transactions/scored` | `transaction-monitor:read` |

Full matrix: [Security Foundation v1](docs/security-foundation-v1.md).

## Audit Logging

Audit Logging v1 records security-relevant analyst write operations in `alert-service`.

Audited actions:

- `SUBMIT_ANALYST_DECISION` on `ALERT`
- `UPDATE_FRAUD_CASE` on `FRAUD_CASE`

Audit events include:

- actor user id
- actor roles and authorities when available
- action
- resource type and id
- timestamp
- `correlationId` when available
- outcome: `SUCCESS`, `REJECTED`, or `FAILED`
- optional failure reason category

Audit payloads intentionally exclude sensitive business details:

- decision reasons and tags
- model feature snapshots
- transaction details
- customer data
- full request payloads

Current sink: structured SLF4J logs through `StructuredAuditEventPublisher`. Durable audit storage is a production follow-up.

Full details: [Security Foundation v1](docs/security-foundation-v1.md).

## Local Development

Prerequisites:

- Docker Desktop or compatible Docker Engine with Compose support.
- Java 21 and Maven for backend tests or local service runs outside Docker.
- Node.js for local frontend development outside Docker.

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

Open the analyst console:

```text
http://localhost:4173
```

The Docker stack starts synthetic replay automatically. Wait about 20-30 seconds after startup and refresh the UI if the first page still shows zero records.

### Demo Auth In Local Runs

The Docker Compose local stack enables demo auth for `alert-service` with:

```text
APP_SECURITY_DEMO_AUTH_ENABLED=true
SPRING_PROFILES_ACTIVE=docker,docker-local
```

Demo auth headers:

| Header | Purpose |
| --- | --- |
| `X-Demo-User-Id` | Authenticates a local analyst user. |
| `X-Demo-Roles` | Comma-separated `AnalystRole` names. |
| `X-Demo-Authorities` | Optional comma-separated authority override for local testing. |

Example read request:

```bash
curl \
  -H "X-Demo-User-Id: analyst-1" \
  -H "X-Demo-Roles: ANALYST" \
  http://localhost:8085/api/v1/alerts
```

Example forbidden write check with read-only role:

```bash
curl \
  -X POST \
  -H "Content-Type: application/json" \
  -H "X-Demo-User-Id: readonly-1" \
  -H "X-Demo-Roles: READ_ONLY_ANALYST" \
  -d '{"analystId":"readonly-1","decision":"CONFIRMED_FRAUD","decisionReason":"manual review","tags":["reviewed"],"decisionMetadata":{}}' \
  http://localhost:8085/api/v1/alerts/{alertId}/decision
```

Expected behavior:

- missing or disabled demo auth: HTTP 401
- authenticated user without required authority: HTTP 403
- invalid demo role/authority: normalized security error

Full details: [Security Foundation v1](docs/security-foundation-v1.md).

## Frontend Analyst Console

URL:

```text
http://localhost:4173
```

Local frontend development:

```bash
cd analyst-console-ui
npm install
npm run dev
```

The Vite dev server proxies `/api` requests to `alert-service` on `http://localhost:8085`.

Security UX:

- The session model uses `userId`, `roles`, and `authorities`.
- `src/auth/session.js` is the UI-facing session contract.
- `src/auth/demoSession.js` is the local demo provider.
- The UI sends demo headers from one API injection point.
- Write actions are disabled when the session lacks the required authority.
- HTTP 401 shows a session-required state.
- HTTP 403 shows an access-denied state.

Frontend checks are not enforcement. They keep the analyst workflow clear while backend authorization remains authoritative.

Full details: [Security Foundation v1](docs/security-foundation-v1.md).

## Path To Production

Current non-production gaps:

- No JWT/OIDC token validation is implemented yet.
- No service-to-service authentication or mTLS is implemented.
- No durable audit store exists yet.
- No read-access audit exists yet.
- The frontend has no production login/OIDC client flow yet.
- Request DTOs still accept `analystId` for compatibility, although secured write paths use the principal as actor source of truth.

Planned production path:

- Add Spring OAuth2 Resource Server support to `alert-service`.
- Configure issuer/JWK settings per environment.
- Map external claims, groups, scopes, or app roles into existing `AnalystAuthority` strings.
- Emit an internal principal shape compatible with `AnalystPrincipal` and `CurrentAnalystUser`.
- Keep the endpoint authorization matrix unchanged.
- Replace the frontend demo provider with an OIDC-backed session source while preserving the `userId`/`roles`/`authorities` UI contract.
- Add durable audit sink if retention or compliance requirements need searchable audit history.

Migration notes: [Security Foundation v1](docs/security-foundation-v1.md).

## Services And Ports

| Service | Port |
| --- | --- |
| `transaction-ingest-service` | `8081` |
| `transaction-simulator-service` | `8082` |
| `feature-enricher-service` | `8083` |
| `fraud-scoring-service` | `8084` |
| `alert-service` | `8085` |
| `ml-inference-service` | `8090` |
| `ollama` | `11434` |
| `analyst-console-ui` | `4173` |
| Kafka | `9092` |
| MongoDB | `27017` |
| Redis | `6379` |

Health endpoints:

- `http://localhost:8081/actuator/health`
- `http://localhost:8082/actuator/health`
- `http://localhost:8083/actuator/health`
- `http://localhost:8084/actuator/health`
- `http://localhost:8085/actuator/health`
- `http://localhost:8090/health`
- `http://localhost:11434`

## Kafka Topics

Topics are initialized by `deployment/init-kafka-topics.sh` through the one-off `kafka-topics-init` compose service.

| Topic | Purpose |
| --- | --- |
| `transactions.raw` | Raw transaction events from ingest and simulator to enrichment. |
| `transactions.enriched` | Enriched feature events from enrichment to scoring. |
| `transactions.scored` | Scored risk events from scoring to alert service. |
| `fraud.alerts` | Alert events emitted by alert service. |
| `fraud.decisions` | Analyst decision events emitted by alert service. |
| `transactions.dead-letter` | Failed records after retries are exhausted. |

## API Surface

Main local endpoints:

- `POST http://localhost:8081/api/v1/transactions`: ingest a transaction.
- `POST http://localhost:8082/api/v1/replay/start`: start replay.
- `POST http://localhost:8082/api/v1/replay/stop`: stop replay.
- `GET http://localhost:8082/api/v1/replay/status`: replay status.
- `GET http://localhost:8085/api/v1/transactions/scored?page=0&size=25`: paged scored transaction monitor data.
- `GET http://localhost:8085/api/v1/alerts`: alert queue.
- `GET http://localhost:8085/api/v1/alerts/{alertId}`: alert details.
- `GET http://localhost:8085/api/v1/alerts/{alertId}/assistant-summary`: assistant case summary.
- `POST http://localhost:8085/api/v1/alerts/{alertId}/decision`: submit analyst decision.
- `GET http://localhost:8085/api/v1/fraud-cases`: fraud case queue.
- `GET http://localhost:8085/api/v1/fraud-cases/{caseId}`: fraud case details.
- `PATCH http://localhost:8085/api/v1/fraud-cases/{caseId}`: update fraud case.

`alert-service` business endpoints under `/api/v1/**` require Security Foundation v1 authentication and authorities.

## Configuration

The platform externalizes environment-specific values and validates critical properties at startup.

Validated configuration groups include:

- Kafka topic names
- Kafka consumer retry settings
- replay source settings
- auto replay settings
- feature store windows and TTL values
- scoring thresholds and scoring mode
- demo auth enablement guardrails in `alert-service`

Expected runtime inputs:

- `KAFKA_BOOTSTRAP_SERVERS`
- `REDIS_HOST`
- `REDIS_PORT`
- `MONGODB_URI`
- `AUTO_REPLAY_ENABLED`
- optional replay dataset paths for `transaction-simulator-service`
- `APP_SECURITY_DEMO_AUTH_ENABLED` for local/dev analyst auth only

Local defaults are provided for development, but invalid overrides fail fast.

## Reliability Retry And DLT

Kafka consumer reliability is configured consistently in:

- `feature-enricher-service`
- `fraud-scoring-service`
- `alert-service`

Current defaults:

- retry attempts: `3`
- retry backoff: `1000 ms`
- dead-letter topic: `transactions.dead-letter`

Failure behavior:

1. Consumer receives a record.
2. Spring Kafka retries with fixed backoff.
3. Retry attempts are logged with service, topic, partition, offset, key, and delivery attempt.
4. Exhausted records are routed to `transactions.dead-letter`.

DLT records indicate processing defects, data defects, or dependency failures. A production workflow should add DLT inspection and controlled replay tooling.

## Logging And Correlation

`correlationId` is the primary cross-service identifier.

REST ingest behavior:

- `transaction-ingest-service` accepts optional `X-Correlation-Id`.
- If absent, a new id is generated.
- The final value is returned in `X-Correlation-Id`.
- The same value is stored in `TransactionRawEvent.correlationId`.

Kafka propagation:

- every event carries `correlationId`
- producers add operational headers such as `correlationId`, `transactionId`, `traceId`, and `alertId`

Structured logs include:

- `transactionId`
- `correlationId`
- `alertId` where applicable
- `topic` where applicable
- retry `partition`, `offset`, and `deliveryAttempt`
- audit actor/action/resource/outcome for analyst writes

## Idempotency And Performance

Current guarantees:

- `transaction-ingest-service` publishes raw events keyed by `transactionId`.
- `feature-enricher-service` checks Redis before loading feature windows and skips already processed customer-scoped transaction ids.
- Redis feature windows use sorted sets keyed by customer and merchant.
- Redis TTLs are applied to transaction, merchant, known-device, processed-transaction, and last-transaction keys.
- `fraud-scoring-service` remains stateless.
- `alert-service` prevents duplicate alert documents with a unique `transactionId` index and treats duplicate-key races as benign duplicate outcomes.

Known tradeoffs:

- Redis idempotency is scoped by `customerId` and `transactionId`.
- A failure after publish but before Redis record can still create duplicate enriched events.
- Alert idempotency protects persisted alert state.
- High-volume production usage should consider pre-aggregated rolling counters or Redis Lua scripts for feature sums.

## Testing

Run backend tests:

```bash
mvn -pl transaction-ingest-service,transaction-simulator-service,feature-enricher-service,fraud-scoring-service,alert-service -am test
```

On Windows with repo-local Maven cache:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl transaction-ingest-service,transaction-simulator-service,feature-enricher-service,fraud-scoring-service,alert-service -am test
```

Run frontend tests and build:

```bash
cd analyst-console-ui
npm test
npm run build
```

Testing layers:

- unit tests for feature calculation, scoring, duplicate handling, alert creation, audit, and replay behavior
- MVC slice tests for validation, request mapping, security status codes, and safe error responses
- frontend tests for API headers, session rendering, 401/403 states, and action gating
- Testcontainers integration tests for Kafka, Redis, and MongoDB when Docker is available
- end-to-end tests for raw ingestion through alert persistence and alert API access

Integration tests are skipped automatically when Docker/Testcontainers is unavailable.

## ML Inference Service

`ml-inference-service` is the Python fraud model runtime used by `fraud-scoring-service`.

Runtime API:

```text
POST /v1/fraud/score
GET /health
```

The Java scoring service sends `MlModelInput`, where `features` is the Java-enriched feature snapshot. The Python service responds with `MlModelOutput`: fraud score, risk level, model metadata, reason codes, score details, and explanation metadata.

Current ML capabilities:

- logistic baseline model
- optional XGBoost adapter when the Python package is installed
- shared Java/Python feature contract
- training, evaluation, local registry, champion/challenger roles
- production feature training mode for inference parity
- SHADOW and COMPARE monitoring
- analyst feedback dataset support

Training smoke test:

```bash
cd ml-inference-service
python -m app.train_model \
  --output tmp_model_artifact.json \
  --evaluation-output tmp_evaluation_report.json \
  --examples 500 \
  --epochs 5 \
  --learning-rate 0.1 \
  --seed 7341 \
  --model-type logistic \
  --training-mode production
```

Verification:

```bash
cd ml-inference-service
python -m unittest discover -s tests
python -m compileall app tests
```

## AI Analyst Assistant

The analyst assistant helps analysts understand cases faster. It does not submit decisions or bypass workflow authorization.

Backend package:

```text
com.frauddetection.alert.assistant
```

Current endpoint:

```text
GET /api/v1/alerts/{alertId}/assistant-summary
```

Current behavior:

- deterministic summaries are the reliable fallback
- Docker can run Ollama locally through `ASSISTANT_MODE=OLLAMA`
- if Ollama or the model is unavailable, `alert-service` falls back to deterministic output
- assistant output includes transaction summary, main fraud reasons, recent customer behavior, recommended next action, supporting evidence, and generation timestamp

Guardrails:

- recommend next actions, do not mutate state
- keep analyst decisions in `/api/v1/alerts/{alertId}/decision`
- expose evidence and uncertainty
- sanitize DTOs before LLM calls
- audit model metadata and correlation context when applicable

## Project Structure

```text
common-events/                  Shared Kafka contracts, enums, and value objects
common-test-support/            Shared test fixtures and Testcontainers helpers
transaction-ingest-service/      External REST transaction ingestion
transaction-simulator-service/   Synthetic replay and generated traffic
feature-enricher-service/        Redis-backed feature enrichment
fraud-scoring-service/           Rule-based and ML-assisted scoring
ml-inference-service/            Python ML model inference service
alert-service/                   Scored transaction projection, alerts, cases, decisions, security, audit
analyst-console-ui/              React analyst console
deployment/                      Docker Compose and Dockerfiles
docs/                            Architecture, security, and review documents
scripts/                         Synthetic dataset and replay scripts
```

## Documentation Index

Security and architecture:

- [Security Foundation v1](docs/security-foundation-v1.md): consolidated technical reference for RBAC, local demo auth, actor identity, audit logging, frontend security UX, JWT/OIDC migration, review notes, known limitations, and next steps.

TODO: If future work adds docs for ML governance, operations, data generation, or deployment, keep them as a small set of consolidated feature documents instead of many prompt-sized files.

## Project Status

Implemented:

- event-driven backend flow
- Docker Compose local stack
- automatic synthetic data bootstrap
- synthetic dataset generators
- React analyst console with pagination
- scored transaction monitor and alert queue
- fraud case management for rapid transfer bursts
- validation and normalized API errors
- retry and dead-letter handling
- structured logging and correlation propagation
- idempotency safeguards
- rule-based scoring with ML extension modes
- Python ML inference service wired in Docker shadow mode
- AI analyst assistant backend and UI summary panel
- Security Foundation v1 for analyst workflow
- local/dev demo auth guardrails
- RBAC endpoint protection
- principal-based actor identity
- audit logging v1

Known production gaps:

- JWT/OIDC Resource Server integration is not implemented yet.
- Service-to-service authentication is not implemented yet.
- Durable audit storage is not implemented yet.
- DLT inspection/replay tooling is not implemented yet.
- The frontend uses local demo session plumbing, not a production login flow.

## Maintainer

Milosz Podsiadly  
[m.podsiadly99@gmail.com](mailto:m.podsiadly99@gmail.com)  
[GitHub - MiloszPodsiadly](https://github.com/MiloszPodsiadly)

## License

Licensed under the [MIT License](https://opensource.org/licenses/MIT).
