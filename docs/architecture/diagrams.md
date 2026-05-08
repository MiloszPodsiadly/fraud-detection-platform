# Architecture Diagrams

Status: current portfolio diagrams.

## Scope

These diagrams are simplified reviewer aids. They summarize implemented service boundaries and known regulated
mutation concepts, but they are not a complete architecture proof and do not replace code-level contracts.

## High-Level Module Flow

```mermaid
flowchart LR
  Ingest[transaction-ingest-service] --> Raw[transactions.raw]
  Simulator[transaction-simulator-service] --> Raw
  Raw --> Enricher[feature-enricher-service]
  Enricher --> Enriched[transactions.enriched]
  Enriched --> Scoring[fraud-scoring-service]
  Scoring --> ML[ml-inference-service]
  Scoring --> Scored[transactions.scored]
  Scored --> Alert[alert-service]
  Alert --> UI[analyst-console-ui]
  Alert --> Trust[audit-trust-authority]
```

The flow is event-driven through Kafka topics, with REST APIs at service boundaries.

## Regulated Mutation Lifecycle

```mermaid
stateDiagram-v2
  [*] --> REQUESTED
  REQUESTED --> AUDIT_ATTEMPTED
  AUDIT_ATTEMPTED --> BUSINESS_COMMITTING
  BUSINESS_COMMITTING --> COMMITTED_EVIDENCE_PENDING
  BUSINESS_COMMITTING --> COMMITTED_EVIDENCE_INCOMPLETE
  COMMITTED_EVIDENCE_PENDING --> COMMITTED_EVIDENCE_CONFIRMED
  REQUESTED --> RECOVERY_REQUIRED
  AUDIT_ATTEMPTED --> RECOVERY_REQUIRED
  BUSINESS_COMMITTING --> COMMIT_UNKNOWN
```

The diagram is simplified. It does not claim external finality or distributed ACID.

## Claim, Replay, Fencing, Renewal, And Checkpoint

```mermaid
sequenceDiagram
  participant Client
  participant API as alert-service API
  participant Coord as regulated coordinator
  participant Mongo
  participant Worker
  Client->>API: request + X-Idempotency-Key
  API->>Coord: canonical intent
  Coord->>Mongo: create or replay command
  Worker->>Mongo: claim lease
  Worker->>Mongo: fenced transition
  Worker->>Mongo: checkpoint/renew lease when allowed
  Client->>API: replay same key
  API->>Mongo: return stored safe status/snapshot
```

Replay is idempotent for the same canonical intent. It is not distributed exactly-once processing.

## Release Governance Flow

```mermaid
flowchart TD
  Commit[Git commit] --> CI[Required CI checks]
  CI --> Digest[Image digest evidence]
  Digest --> Manifest[Release manifest]
  Manifest --> Owner[Single release owner review]
  Owner --> Env[External platform controls]
  Env --> Decision{Enablement decision}
  Decision -->|Approved separately| ConfigPR[Separate config PR]
  Decision -->|Not approved| NoGo[NO-GO]
```

FDP-40 and later release-control docs are readiness evidence. They are not production approval by themselves.

## Alert To Case Roadmap

```mermaid
flowchart LR
  Alert[High-risk alert] --> Decision[Analyst decision]
  Decision --> Case[Fraud case]
  Case --> Review[Reviewer workflow]
  Review --> Roadmap[Future case-management hardening]
```

Case-management hardening beyond the current implementation must be treated as roadmap until implemented.
