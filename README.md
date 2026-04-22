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
- [ML Inference Service](#ml-inference-service)
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
- Python 3.12 for ML inference service
- Ollama for local LLM assistant runtime in Docker
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

The generated target scenario mix is intentionally dominated by legitimate traffic:

- `LOW`: about 99.55%
- `MEDIUM`: about 0.20%
- `HIGH`: about 0.20%
- `CRITICAL`: about 0.05%

`LOW` and `MEDIUM` transactions are visible in the scored transaction monitor. Only `HIGH` and `CRITICAL` transactions create analyst alerts.

### Automatic Docker Data Bootstrap

`transaction-simulator-service` starts automatic replay in Docker through these compose values:

```yaml
AUTO_REPLAY_ENABLED: "true"
AUTO_REPLAY_SOURCE_TYPE: SYNTHETIC
AUTO_REPLAY_MAX_EVENTS: "50000"
AUTO_REPLAY_THROTTLE_MILLIS: "5"
AUTO_REPLAY_START_DELAY_MILLIS: "15000"
```

This publishes 50,000 generated transactions after a short delay. The current generator uses a deterministic 1,000-event cycle with mostly normal LOW traffic and rare review scenarios near the end of each block. Rapid-transfer cases use 2 or 3 varied transfer amounts inside 1 minute, each transfer can still score LOW on its own, and the grouped case is created when the combined converted value reaches at least 20,000 PLN. This keeps the dataset large while preventing the alert queue from being flooded by thousands of suspicious transactions.

### Bash Data Scripts

Run scripts from Git Bash, WSL, Linux, or macOS.

Generate dimensions:

```bash
bash scripts/generate-dimensions.sh
```

Generate canonical `TransactionRawEvent` JSONL:

```bash
bash scripts/generate-canonical-replay.sh --count 50000
```

Generate replay data plus CSV labels:

```bash
bash scripts/generate-labelled-dataset.sh --count 50000
```

Override distribution:

```bash
bash scripts/generate-labelled-dataset.sh \
  --count 50000 \
  --normal-percentage 93 \
  --rapid-transfer-seed-percentage 2 \
  --rapid-transfer-percentage 1 \
  --new-device-percentage 1 \
  --high-proxy-percentage 1 \
  --country-mismatch-percentage 1 \
  --account-takeover-percentage 1
```

Generate durable replay JSONL:

```bash
bash scripts/generate-synthetic-replay.sh --count 50000
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
- paginated alert review queue capped at 100 alerts per request
- rapid-transfer burst fraud case panel for `RAPID_TRANSFER_BURST_20K_PLN`
- alert details page
- AI assistant case summary with fraud reasons, behavior facts, and suggested review actions
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
- `ml-inference-service`: `8090`
- `ollama`: `11434`
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
- `http://localhost:8090/health`
- `http://localhost:11434`

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
- `GET http://localhost:8085/api/v1/alerts/{alertId}/assistant-summary`: AI assistant case summary.
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
- `ML`: Python ML final path, with rule-based fallback if unavailable.
- `SHADOW`: rule-based final decision with ML diagnostics attached.
- `COMPARE`: rule-based final decision with rule-vs-ML comparison diagnostics.

The Docker stack starts `ml-inference-service`, a Python HTTP model service exposed on port `8090`. Docker defaults `fraud-scoring-service` to `SHADOW` mode so rule-based decisions remain final while Python ML diagnostics are attached. Override `SCORING_MODE=ML` to use the Python model as the final decision source with rule-based fallback.

ML runtime configuration:

```yaml
SCORING_MODE: SHADOW
ML_MODEL_BASE_URL: http://ml-inference-service:8090
ML_MODEL_CONNECT_TIMEOUT: 500ms
ML_MODEL_READ_TIMEOUT: 1500ms
```

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

## ML Inference Service

`ml-inference-service` is the Python fraud model runtime used by `fraud-scoring-service`.
The public scoring API remains compatible with the Java scoring client:

```text
POST /v1/fraud/score
GET /health
```

`fraud-scoring-service` sends `MlModelInput`, where `features` is the Java-enriched feature snapshot.
The ML service responds with the existing `MlModelOutput` shape: `fraudScore`, `riskLevel`, `modelName`,
`modelVersion`, `reasonCodes`, `scoreDetails`, and `explanationMetadata`.

### ML Package Layout

```text
ml-inference-service/app/
  data/          synthetic datasets and Dataset abstraction
  evaluation/    fraud metrics and threshold reports
  features/      shared feature contract and FeaturePipeline
  feedback/      analyst feedback datasets
  inference/     runtime response assembly
  models/        logistic baseline and optional model adapters
  registry/      local model registry
  training/      training and retraining workflows
```

### Training And Evaluation

Run a small local training smoke test:

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

Training writes an evaluation report with fraud-focused metrics:

- PR-AUC
- ROC-AUC
- precision@k
- recall@k
- alert rate
- fraud capture rate
- false positive rate
- cost curves
- threshold analysis
- optimal threshold by F1
- optimal threshold by expected cost

Evaluation is split-based, not in-sample. The training pipeline uses train,
validation, and test splits. The default `training-mode=production` trains only
on `productionInferenceFeatures` from the shared feature contract, so the model
feature schema matches the Java-enriched production inference payload. `full`
mode remains available for offline experiments that intentionally include
training-only synthetic features.

Splitting supports `random`, `temporal`, and `out_of_time` modes. Temporal
splits preserve timestamp ordering and, when enough fraud cases exist, stratify
fraud labels across train, validation, and test. The validation split selects the
operating threshold, the test split reports final metrics, and the evaluation
report also includes out-of-time metrics to show degradation on a later unseen
time window.

Logistic regression and XGBoost use the same training lifecycle: split dataset,
fit on train, select threshold on validation, evaluate on test, evaluate an
out-of-time window, write the same report schema, save the artifact, and
optionally register it.

### Local Model Registry

The local registry stores copied model artifacts plus metadata:

- `modelVersion`
- `modelType`
- metrics
- training metadata
- role: `champion`, `challenger`, or `archived`

Register a trained artifact:

```bash
cd ml-inference-service
python -m app.train_model \
  --output tmp_model_artifact.json \
  --evaluation-output tmp_evaluation_report.json \
  --register-model \
  --registry-role challenger
```

Runtime loading order:

1. explicit `model_version`
2. champion registry entry
3. latest registry entry
4. legacy `app/model_artifact.json`

Artifact metadata controls runtime model loading. `modelType=logistic` loads the
logistic adapter. `modelType=xgboost` loads the optional XGBoost adapter when the
`xgboost` Python package is installed. Unknown model types fail clearly instead
of falling back to logistic. Artifacts store `trainingMode`, `featureSetUsed`,
and `featureSchema` for inference-time parity checks.

### Feature Parity

The shared fraud feature contract distinguishes production inference features
from training-only synthetic features.

Current production Java snapshots provide:

- `recentTransactionCount`
- `recentAmountSum`
- `transactionVelocityPerMinute`
- `merchantFrequency7d`
- `deviceNovelty`
- `countryMismatch`
- `proxyOrVpnDetected`

Python derives these production inference features from the Java snapshot:

- `highRiskFlagCount`
- `rapidTransferBurst`

These features are currently training-only/synthetic-only and are not required
from the Java production path:

- `transactionVelocityPerHour`
- `transactionVelocityPerDay`
- `recentAmountAverage`
- `recentAmountStdDev`
- `amountDeviationFromUserMean`
- `merchantEntropy`
- `countryEntropy`

The inference runtime reports feature compatibility in `scoreDetails` so missing
required production fields can be detected without pretending every training
feature is available in production.

Training artifacts store the exact feature set used by the model. Runtime scoring
uses the artifact training mode, so a production-trained model sees the same
ordered feature schema during training and inference.

### Retraining Decisions

Challenger promotion uses multiple criteria instead of PR-AUC alone:

- PR-AUC must improve.
- false positive rate must stay within the configured increase threshold.
- alert rate must remain inside the configured business range.
- expected cost must not worsen.
- budget-constrained performance must not regress when an alert budget is configured.
- high-priority segment performance must not severely regress.
- out-of-time stability must stay within configured degradation thresholds.

The comparison output includes a structured decision object with pass/fail
criteria, threshold settings, and the current/challenger metrics used for the
decision.

The rollout decision object is governance-oriented:

- `decision`: `promote`, `shadow_only`, or `reject`
- `summary`
- `passed_checks`
- `failed_checks`
- `key_metrics`
- `recommended_rollout_mode`
- `recommended_alert_budget`
- `evaluation_window_metadata`

### ML Compare

The Python runtime can compare two ML runtimes, typically registry champion vs
challenger. The comparison reports per-model version metrics, score delta,
absolute score delta, risk-level mismatch, decision disagreement, and threshold
differences.

### Analyst Feedback

Analyst feedback rows are privacy-safe JSONL records derived from `FraudDecisionEvent` metadata.
Transaction identifiers are hashed, delayed labels are supported, and only resolved analyst decisions
are used for training:

- `CONFIRMED_FRAUD` -> `1`
- `MARKED_LEGITIMATE` -> `0`
- unresolved decisions are excluded until updated

### ML Verification

```bash
cd ml-inference-service
python -m unittest discover -s tests
python -m compileall app tests
```

## AI/ML Roadmap

Rule-based scoring remains the local default production-safe path while Docker now runs a Python ML inference service in shadow mode.

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

Current Python inference boundary:

- service path: `ml-inference-service`
- scoring endpoint: `POST /v1/fraud/score`
- health endpoint: `GET /health`
- model family: versioned logistic fraud model implemented and trained in Python without external runtime dependencies
- model artifact: `ml-inference-service/app/model_artifact.json`
- training entrypoint: `python -m app.train_model --output ml-inference-service/app/model_artifact.json`
- response contract: `MlModelOutput`, including model name, version, timestamp, reason codes, score details, and explanation metadata

Current ML capabilities:

- clean Python pipeline architecture for data, features, training, evaluation, inference, feedback, registry, and models
- user-sequence synthetic data generation with normal behavior and labelled fraud scenarios
- shared Java/Python feature contract in `common-events`
- split-based fraud-specific evaluation metrics and JSON reports
- production feature training mode with artifact feature schema parity
- stratified temporal and out-of-time validation support
- cost-based threshold analysis
- fixed alert-budget evaluation for analyst queue constraints
- segment evaluation by customer segment, merchant category, country, and fraud scenario when available
- stability assessment across temporal and out-of-time windows
- analyst feedback datasets with delayed label updates
- model-agnostic multi-criteria retraining comparison for challenger models
- local registry with latest, version, champion, challenger, and promotion support
- SHADOW and COMPARE monitoring for score distribution, score deltas, disagreement, risk mismatch, model version tracking, and ML-vs-ML comparison

Model support:

- logistic regression is the default runnable model type
- XGBoost is optional; when the `xgboost` package is installed, the adapter supports fit, predict, save, load, registry artifacts, and inference through the same feature pipeline

Explanation strategy:

- rule-based explanations use weighted reason codes
- ML explanations populate `explanationMetadata` and `scoreDetails` with model feature contributions
- future model versions can add methods such as `SHAP`, `FEATURE_IMPORTANCE`, or `MODEL_NATIVE_REASON_CODES`
- detailed explanation values should live in `scoreDetails`

Deployment guidance:

- keep model inference outside Kafka listeners
- keep model metadata explicit for auditability and rollback
- preserve `TransactionScoredEvent` compatibility
- add canary/shadow mode before replacing rule-based scoring

## AI Analyst Assistant Roadmap

The AI analyst assistant helps analysts understand cases faster, but it does not own fraud decisions or bypass the alert workflow.

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
2. Keep deterministic summaries as the default reliable assistant path.
3. Add an optional LLM adapter behind a port such as `CaseNarrativeGenerator`.
4. Keep analyst decisions flowing through `/api/v1/alerts/{alertId}/decision`.
5. Emit decisions as `FraudDecisionEvent`.

Current endpoint:

```text
GET /api/v1/alerts/{alertId}/assistant-summary
```

The response includes transaction summary, main fraud reasons, customer recent behavior, a recommended next action, supporting evidence, and generation timestamp. The analyst console renders this on the alert detail page.

Local LLM runtime:

- Docker Compose starts `ollama` on port `11434`.
- `ollama-model-init` pulls `llama3.2:3b` on first startup.
- `alert-service` uses `ASSISTANT_MODE=OLLAMA` in Docker.
- If Ollama or the model is unavailable, the service falls back to deterministic assistant output.

Assistant runtime configuration:

```yaml
ASSISTANT_MODE: OLLAMA
OLLAMA_BASE_URL: http://ollama:11434
OLLAMA_MODEL: llama3.2:3b
ASSISTANT_CONNECT_TIMEOUT: 500ms
ASSISTANT_READ_TIMEOUT: 45s
```

No OpenAI API key or external LLM provider is required. The first Docker startup needs network access to download the Ollama model into the `ollama-data` volume.

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
ml-inference-service/            Python ML model inference service
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
- Python ML inference service wired in Docker shadow mode
- AI analyst assistant backend and UI summary panel

## 👤 Maintainer

**Milosz Podsiadly**  
📧 [m.podsiadly99@gmail.com](mailto:m.podsiadly99@gmail.com)  
🔗 [GitHub – MiloszPodsiadly](https://github.com/MiloszPodsiadly)

---

## 🪪 License

Licensed under the [MIT License](https://opensource.org/licenses/MIT).
