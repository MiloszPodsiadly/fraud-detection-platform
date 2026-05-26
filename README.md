# Fraud Detection Platform

Production-style fraud detection platform implemented as a multi-service monorepo. The system ingests or generates
transactions, enriches them with behavioral features, scores fraud risk, creates analyst alerts and fraud cases, and
supports secured analyst workflows with RBAC, audit logging, evidence tracking, and local observability.

The repository intentionally uses platform-owned synthetic data. Third-party fraud datasets should stay local and
outside public Git history.

## Contents

- [System Overview](#system-overview)
- [Architecture](#architecture)
- [Runtime Flow](#runtime-flow)
- [Security And Audit Boundaries](#security-and-audit-boundaries)
- [Local Development](#local-development)
- [Services And Ports](#services-and-ports)
- [Testing](#testing)
- [Documentation Map](#documentation-map)
- [Project Layout](#project-layout)
- [Production Posture](#production-posture)

## System Overview

The platform is built around explicit service ownership and event-driven handoff:

| Area | Owner | Responsibility |
| --- | --- | --- |
| Transaction ingestion | `transaction-ingest-service` | REST entry point for submitted transactions. |
| Synthetic replay | `transaction-simulator-service` | Local/demo traffic generation from synthetic scenarios. |
| Feature enrichment | `feature-enricher-service` | Redis-backed feature windows and derived fraud signals. |
| Fraud scoring | `fraud-scoring-service` | Rule scoring plus ML integration in rule, shadow, ML, or compare modes. |
| ML runtime | `ml-inference-service` | Python model inference, governance snapshots, drift and advisory endpoints. |
| Alert workflow | `alert-service` | Alerts, fraud cases, analyst decisions, RBAC, audit, recovery, and regulated mutation controls. |
| Trust authority | `audit-trust-authority` | Local signing and verification authority for audit anchor material. |
| Analyst UI | `analyst-console-ui` | React console for monitoring, alert review, case workflow, and security UX. |
| Shared contracts | `common-events` | Kafka event contracts and shared value objects. |

Core capabilities:

- Kafka-based transaction pipeline with bounded local service responsibilities.
- Fraud scoring with deterministic rule mode and ML-assisted modes.
- Alert and fraud-case workflows for analyst review.
- Backend-authoritative RBAC and frontend session-aware UX.
- Append-only platform audit records and local trust-authority signing.
- Local Prometheus/Grafana observability.
- CI evidence mapping and branch-evidence governance for FDP work.

## Architecture

![Fraud Detection Platform architecture](docs/assets/readme_architecture.svg)

Design boundaries:

- Kafka contracts live in `common-events`.
- REST DTOs and persistence models stay service-local unless deliberately promoted.
- Backend authorization is authoritative; frontend gating is only UX.
- Mongo transaction mode is a local boundary, not distributed ACID.
- Kafka/outbox delivery remains asynchronous and at-least-once unless a later implemented control proves otherwise.

## Runtime Flow

![Fraud detection runtime flow](docs/assets/readme_runtime_flow.svg)

Risk scoring modes:

| Mode | Final score owner | Purpose |
| --- | --- | --- |
| `RULE_BASED` | Java rules | Deterministic default scoring. |
| `SHADOW` | Java rules | Attach ML diagnostics without changing final decisions. |
| `COMPARE` | Java rules | Compare rules and ML for operational analysis. |
| `ML` | Python model | Use ML result with rule fallback when unavailable. |

## Security And Audit Boundaries

![Security and audit boundaries](docs/assets/readme_security_audit.svg)

Security model:

- Demo auth is local/dev only and guarded by profile checks.
- Local OIDC uses Keycloak through the Docker override.
- JWT Resource Server support maps external claims into `AnalystPrincipal`.
- Actor identity for secured write paths comes from the authenticated principal, not request payload fields.
- RBAC authorities protect analyst, audit, recovery, outbox, and trust incident endpoints.

Audit and trust model:

- Analyst write actions and governance advisory reviews are persisted as append-only audit records.
- Regulated mutation and outbox controls are local evidence controls, not external finality.
- The local trust authority signs and verifies audit anchor material for local proof workflows.
- The repository does not claim WORM storage, legal notarization, bank certification, distributed ACID, or exactly-once Kafka.

Start with:

- [Security architecture](docs/security/security_architecture.md)
- [Current architecture](docs/architecture/current_architecture.md)
- [Alert service source of truth](docs/architecture/alert_service_source_of_truth.md)
- [Public API semantics](docs/api/public_api_semantics.md)

## Local Development

Docker is the supported local runtime path for this README.

### Quick Start

Prerequisites are Docker with Compose and OpenSSL. On macOS or Linux with GNU Make installed, from a fresh clone:

```bash
make app-up
```

On Windows with Docker Desktop and Git for Windows installed, run the equivalent one-command startup from
PowerShell:

```powershell
.\scripts\app.cmd up
```

The Windows launcher uses Git for Windows to run the existing OpenSSL-based fixture generator and starts the
same Compose overlay combination as `make app-up`. From Git Bash on Windows without GNU Make, use
`./scripts/app.cmd up`.

OpenSSL is used only to generate local identity fixture material. Private PEM keys are not committed to this
repository.

### Most Complete Local Security Demonstration Stack

`make app-up`, or `.\scripts\app.cmd up` on Windows, starts the most complete local security demonstration stack
currently provided by the repository. It:

- creates `deployment/.env` from `deployment/.env.example` if it is missing;
- runs `scripts/bootstrap-local-fixtures.sh` to generate local mTLS and JWT material under
  `deployment/.local/service-identity/`;
- starts OIDC browser login, mTLS internal calls to ML, JWT identity for the local trust authority, local
  observability, and the application container hardening overlay.

`deployment/.local/` is ignored by Git and excluded from Docker build contexts. Generated private keys stay on
the local workstation and can be replaced by rerunning the selected startup command.

For manual inspection, the equivalent Compose startup after running `bash scripts/bootstrap-local-fixtures.sh`
is:

```bash
docker compose --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  -f deployment/docker-compose.dev.yml \
  -f deployment/docker-compose.oidc.yml \
  -f deployment/docker-compose.service-identity-mtls.yml \
  -f deployment/docker-compose.trust-authority-jwt.yml \
  -f deployment/docker-compose.hardened.yml \
  up --build -d
```

`deployment/.env` is a committed local demo/evaluation configuration fixture, so the project remains runnable for
evaluation without claiming secret management. Application startup guards reject demo internal-auth
patterns and local-HMAC trust-authority demo configuration outside `local`, `dev`, or `docker-local` profiles,
or an automated `test` context with an explicit fixture marker such as `LOCAL_FIXTURE_TEST_ENABLED=true`.
`deployment/.env.example` documents the expected variables.
The generic `test` profile is not a deployment mode, and generic `docker` is not a local-secret allowlist by
itself.

After startup, confirm container readiness:

```bash
docker compose --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  -f deployment/docker-compose.dev.yml \
  -f deployment/docker-compose.oidc.yml \
  -f deployment/docker-compose.service-identity-mtls.yml \
  -f deployment/docker-compose.trust-authority-jwt.yml \
  -f deployment/docker-compose.hardened.yml \
  ps
```

On Windows, the equivalent status command is:

```powershell
.\scripts\app.cmd ps
```

Expected resolved security-relevant values for this exact command:

| Setting | Expected resolved value |
| --- | --- |
| `ml-inference-service.INTERNAL_AUTH_MODE` | `MTLS_SERVICE_IDENTITY` |
| `fraud-scoring-service.INTERNAL_AUTH_CLIENT_ENABLED` | `true` |
| `alert-service.INTERNAL_AUTH_CLIENT_ENABLED` | `true` |
| `alert-service.APP_SECURITY_DEMO_AUTH_ENABLED` | `false` |
| `alert-service.AUDIT_TRUST_AUTHORITY_ENABLED` | `true` |
| `alert-service.AUDIT_TRUST_AUTHORITY_SIGNING_REQUIRED` | `true` |
| `alert-service.ASSISTANT_MODE` | `DETERMINISTIC` |
| `analyst-console-ui` build arg `VITE_AUTH_PROVIDER` | `bff` |

CI renders this official complete local security demonstration combination and runs `scripts/check-compose-security-config.mjs`; a
wrong official overlay order that leaves local-only internal auth enabled fails that resolved-config assertion.

### Security Scope Of The Local Stack

This is not a production deployment or production security evidence. `deployment/.env` is intentionally committed for local evaluation, and the
documented stack publishes ports only on localhost. Startup guards reject committed demo internal-auth patterns
and local-HMAC trust-authority demo configuration unless an allowed fixture profile is active: `local`, `dev`, or
`docker-local`; `test` requires an explicit automated-fixture marker. Other local evaluation credentials in the
fixture are not a production secret-management mechanism. Keycloak runs in dev mode. The local signing authority
is a local trust-authority simulation, not an independent external trust anchor. The application container hardening overlay
covers application containers only; it does not harden all third-party infrastructure images. Production requires
external secret management, a production identity provider, an independent trust anchor, managed TLS termination,
image provenance controls, and environment-specific deployment controls.

Generated mTLS and JWT material in `deployment/.local/service-identity/` is local evaluation material only. It is
not production PKI, production provenance, or independent external trust anchoring.

CI includes repository filesystem scanning for critical known vulnerabilities as review visibility only. It is
not production image provenance; follow-up controls include digest pinning, SBOM generation, SLSA/provenance
evidence, signed images and automated dependency updates.

| Stack or overlay | Purpose | Uses demo secrets? | Production suitable? |
| --- | --- | --- | --- |
| Base | Full internal-only application stack and durable local dependencies, without host port publication. | Yes; the local trust authority has an HMAC fixture default. | No |
| Dev | Local ports, demo auth and local service fixture wiring. | Yes | No |
| OIDC local demo | Keycloak dev-mode browser login/BFF exercise. | Yes | No |
| mTLS service identity local demo | Certificate-backed ML calls using generated local certificates. | Yes | No |
| Trust-authority JWT local demo | JWT-authenticated calls to the local signing authority. | Yes | No |
| Application container hardening overlay | Read-only Java, ML and UI containers with reduced application-container privileges for local verification. | Inherits selected stack. | No |

#### What This Does Not Protect Against

- Hostile users with access to the local workstation or committed fixture material.
- Lateral movement from a compromised container inside the local Docker network.
- Compromise of third-party infrastructure images outside the application hardening overlay.
- Protection of real regulated data.
- Production PKI or independent trust anchoring.
- No legal notarization or WORM-compliant retention.

### Compose Overlay Order Matters

Compose applies later overlay values over earlier values. `docker-compose.dev.yml` intentionally selects
local-only ML authentication, while the mTLS overlay must come after it to produce
`INTERNAL_AUTH_MODE=MTLS_SERVICE_IDENTITY` in the complete local security demonstration stack.
The documented stack does not start Ollama or pull a model, and CI intentionally never pulls a local LLM model.
Backend JVM tuning can be overridden using
`JAVA_TOOL_OPTIONS`; application images use an exec-form Java entrypoint.

Ollama is available only as an explicit local opt-in:

```bash
docker compose --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  -f deployment/docker-compose.dev.yml \
  -f deployment/docker-compose.ai.yml \
  up --build -d
```

Open:

- Analyst console: `http://localhost:4173`
- Alert service: `http://localhost:8085`
- ML inference service: `https://localhost:8090` (generated local demo CA from `deployment/.local/service-identity/mtls`; Compose verifies it in-container)
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (initial local default: `admin` / `admin`; `grafana-data` retains password changes)
- Keycloak: `http://localhost:8086`

### Persistent Local Data And Cleanup

Named volumes persist data between restarts:

| Volume | Persisted local material |
| --- | --- |
| `mongodb-data` | MongoDB databases used by alerts, ML governance and the local trust authority. |
| `redis-data` | Redis local state and Redis persistence files produced by the local container. |
| `prometheus-data` | Local metrics history. |
| `grafana-data` | Local Grafana state. |

Stop the complete local security demonstration stack without deleting local data:

```bash
make app-down
```

On Windows:

```powershell
.\scripts\app.cmd down
```

The equivalent manual command is:

```bash
docker compose --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  -f deployment/docker-compose.dev.yml \
  -f deployment/docker-compose.oidc.yml \
  -f deployment/docker-compose.service-identity-mtls.yml \
  -f deployment/docker-compose.trust-authority-jwt.yml \
  -f deployment/docker-compose.hardened.yml \
  down
```

Delete all named data volumes for the complete local security demonstration stack:

```bash
make app-clean
```

On Windows:

```powershell
.\scripts\app.cmd clean
```

The equivalent manual command is:

```bash
docker compose --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  -f deployment/docker-compose.dev.yml \
  -f deployment/docker-compose.oidc.yml \
  -f deployment/docker-compose.service-identity-mtls.yml \
  -f deployment/docker-compose.trust-authority-jwt.yml \
  -f deployment/docker-compose.hardened.yml \
  down -v
```

### Troubleshooting

- Docker with Compose and OpenSSL are required for `make app-up`; the Windows launcher obtains Bash and OpenSSL from Git for Windows.
- Delete `deployment/.local/` and rerun the selected startup command to regenerate local certificate/JWT fixture material.
- `make app-clean` or `.\scripts\app.cmd clean` removes named local data volumes as well as stopping the demonstration stack.
- Run `bash scripts/bootstrap-local-fixtures.sh` before local fixture-dependent backend or ML identity tests.

## Services And Ports

These local bindings are supplied by `deployment/docker-compose.dev.yml`; core services in the base file do not
publish host ports.

| Service | Local URL | Notes |
| --- | --- | --- |
| `analyst-console-ui` | `http://localhost:4173` | React analyst console served by nginx. |
| `transaction-ingest-service` | `http://localhost:8081` | REST transaction ingestion. |
| `transaction-simulator-service` | `http://localhost:8082` | Synthetic replay and generated traffic. |
| `feature-enricher-service` | `http://localhost:8083` | Feature windows and enrichment. |
| `fraud-scoring-service` | `http://localhost:8084` | Rule and ML-assisted scoring. |
| `alert-service` | `http://localhost:8085` | Alerts, cases, audit, RBAC, recovery APIs. |
| `ml-inference-service` | `https://localhost:8090` | Python inference and governance API over the documented stack's local demo TLS certificate. |
| `audit-trust-authority` | `http://localhost:8095` | Local audit-signing authority. |
| `keycloak` | `http://localhost:8086` | Keycloak dev-mode OIDC in the documented stack. |
| `kafka` | `127.0.0.1:9092` | Local broker. |
| `mongodb` | `127.0.0.1:27017` | Local persistence. |
| `redis` | `127.0.0.1:6379` | Feature windows and local state. |
| `prometheus` | `http://localhost:9090` | Metrics and alert rules. |
| `grafana` | `http://localhost:3000` | Provisioned dashboards. |

## Container Health

| Service | Endpoint | Auth behavior | Compose check |
| --- | --- | --- | --- |
| Java application services | `/actuator/health/readiness` | Actuator readiness exposure; alert permits this health route through its public technical security rule. | `curl -fsS` from the container |
| `ml-inference-service` | `/health` | Public technical health response; the mTLS overlay changes transport to HTTPS and checks it with the local CA. | Python `urllib.request` over HTTPS from the container |
| `analyst-console-ui` | `/` | Static nginx response only. | `wget` to `127.0.0.1:8080` from the container |
| Kafka, MongoDB, Redis | Native check | No application auth surface. | Broker/database/CLI command |
| Prometheus, Grafana, Keycloak | None configured | Supporting local services; Keycloak is started in dev mode. | Not health-gated by Compose |

## Testing

Backend:

```bash
bash scripts/bootstrap-local-fixtures.sh
mvn test
```

Single backend module:

```bash
mvn -pl alert-service -am test
```

Frontend:

```bash
cd analyst-console-ui
npm test
npm run build
```

ML service:

```bash
cd ml-inference-service
python -m unittest discover -s tests
python -m compileall app tests
```

Documentation and CI governance checks:

```bash
node scripts/check-doc-overclaims.mjs
node scripts/compare-ci-jobs.mjs
node scripts/check-fdp-scope-helpers-smoke.mjs
```

Integration tests use Docker/Testcontainers where applicable and are skipped automatically when Docker is not
available.

## Documentation Map

The README is intentionally short. Detailed contracts live in focused documentation:

| Need | Start here |
| --- | --- |
| Current repository docs | [Documentation index](docs/index.md) |
| Architecture and diagrams | [Architecture documentation](docs/architecture/index.md) |
| API contracts and error semantics | [API documentation](docs/api/index.md) |
| Security, auth, RBAC, audit | [Security documentation](docs/security/index.md) |
| Fraud-case lifecycle | [Fraud case management](docs/product/fraud_case_management.md) |
| ML governance and drift | [ML governance and drift](docs/ml/ml_governance_drift_v1.md) |
| Observability | [Operations and observability](docs/observability/operations_observability_v2.md) |
| FDP branch records | [FDP branch evidence](docs/fdp/index.md) |
| CI evidence mapping | [CI evidence map](docs/ci_evidence_map.md) |
| Reviewer flow | [Reviewer checklist](docs/reviewer_checklist.md) |

## Project Layout

```text
common-events/                  Shared Kafka contracts and value objects
common-test-support/            Shared fixtures and Testcontainers helpers
transaction-ingest-service/      REST transaction ingestion
transaction-simulator-service/   Synthetic replay and generated traffic
feature-enricher-service/        Redis-backed feature enrichment
fraud-scoring-service/           Rule and ML-assisted scoring
ml-inference-service/            Python model inference and governance API
alert-service/                   Alerts, cases, audit, RBAC, recovery APIs
audit-trust-authority/           Local trust-signing authority
analyst-console-ui/              React analyst console
deployment/                      Docker Compose, service images, monitoring config
docs/                            Architecture, API, security, runbooks, FDP evidence
scripts/                         CI, docs, scope, and dataset helpers
```

## Production Posture

Implemented production-style foundations:

- Service boundaries with Kafka contracts and local persistence ownership.
- RBAC and backend-authoritative authorization for analyst workflows.
- Durable local audit records and bounded integrity/trust workflows.
- Regulated mutation, outbox, and recovery evidence for selected workflows.
- Local OIDC, service identity foundations, and mTLS-scoped internal calls.
- CI gates and documentation evidence for branch-level changes.

Explicit non-claims:

- No bank certification.
- No production deployment approval.
- No legal notarization or WORM certification.
- No distributed ACID.
- The platform does not provide exactly-once Kafka delivery.
- No external finality unless a current source-of-truth document names the implemented control and its limitations.

## Maintainer

Milosz Podsiadly  
[m.podsiadly99@gmail.com](mailto:m.podsiadly99@gmail.com)  
[GitHub - MiloszPodsiadly](https://github.com/MiloszPodsiadly)

## License

Licensed under the [MIT License](https://opensource.org/licenses/MIT).
