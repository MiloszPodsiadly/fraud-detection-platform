# Current Architecture

Status: current architecture summary.

## Scope

This document describes the current repository architecture at a review level. It is not a production
certification, a bank enablement decision, or a replacement for service-specific contracts.

## System Map

| Area | Current owner | Boundary |
| --- | --- | --- |
| Transaction ingestion | `transaction-ingest-service` | REST input and Kafka publication. |
| Synthetic replay | `transaction-simulator-service` | Local/demo traffic generation only. |
| Feature enrichment | `feature-enricher-service` | Redis-backed feature windows and enriched events. |
| Fraud scoring | `fraud-scoring-service` | Rule scoring and ML integration client boundary. |
| ML inference | `ml-inference-service` | Python model runtime and governance endpoints. |
| Alert workflow | `alert-service` | Alerts, cases, regulated mutations, audit, recovery, and RBAC. |
| Audit trust authority | `audit-trust-authority` | Local signing/verification service for audit anchor material. |
| Analyst UI | `analyst-console-ui` | Frontend UX only; backend authorization remains authoritative. |
| Shared contracts | `common-events` | Kafka event contracts and shared event value types. |

## Critical Invariants

1. Backend authorization is authoritative; frontend controls are only UX.
2. Regulated mutation idempotency keys bind to canonical intent and backend-resolved actor identity.
3. Local Mongo transaction mode is a local boundary only; it is not distributed ACID.
4. Kafka/outbox delivery remains asynchronous and at-least-once unless a future control proves otherwise.
5. Historical FDP proof docs remain traceable but may be superseded by current release-governance docs.

## Areas Not To Change Casually

- Public API statuses and error semantics.
- Regulated mutation state transitions, leases, checkpoint renewal, replay, and recovery.
- Kafka event contracts in `common-events`.
- RBAC authority names and principal mapping.
- Audit, outbox, trust, and release-governance non-claim wording.

## Current Non-Claims

The repository does not currently prove bank certification, production deployment approval, WORM storage,
legal notarization, external finality, distributed ACID, or exactly-once Kafka delivery.

## Related Docs

- [Documentation style guide](../documentation-style-guide.md)
- [API surface](../api/api-surface-v1.md)
- [Security foundation](../security/security-foundation-v1.md)
- [Alert service source of truth](alert-service-source-of-truth.md)

