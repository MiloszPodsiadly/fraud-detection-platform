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
- [Operations And Observability](#operations-and-observability)
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
- RBAC, security error handling, and audit logging for analyst actions and governance advisory review

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
| `audit-trust-authority` | Local-first independent audit anchor signing authority for FDP-23; owns demo Ed25519 private key material outside alert-service. |
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
- Audit logging: analyst write actions are persisted append-only and emitted as structured audit events; governance advisory human-review entries are persisted append-only.

## Security Foundation v1

Security Foundation v1 protects the analyst workflow owned by `alert-service` and consumed by `analyst-console-ui`.

Implemented:

- Authentication skeleton for local/dev demo auth.
- Spring OAuth2 Resource Server skeleton for JWT validation.
- JWT to `AnalystPrincipal` conversion with configurable claim mapping.
- RBAC with `AnalystRole` personas and `AnalystAuthority` enforcement strings.
- Endpoint protection for analyst APIs under `alert-service` `/api/v1/**`.
- Stable JSON HTTP 401/403 responses.
- Principal-based actor identity for analyst write paths.
- Audit logging v1 for alert decisions and fraud case updates.
- Append-only governance advisory audit trail for authenticated human review.
- Frontend session awareness, explicit session lifecycle states, role-aware action disabling, and dedicated auth/security states.
- JWT/OIDC migration extension points.

Important security boundaries:

- Demo auth is local/dev only. It is disabled by default and controlled by `app.security.demo-auth.enabled`.
- Demo auth requires an allowed profile: `local`, `dev`, `docker-local`, or `test`.
- If demo auth is enabled outside those profiles, `alert-service` rejects startup.
- Demo auth does not coexist with JWT auth. If JWT is enabled, demo auth headers are ignored.
- Demo auth is not the production authentication path.
- JWT auth uses Spring Resource Server and maps external claims into the same internal principal and authority model used by demo auth.
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
- `governance-advisory:audit:write`
- `audit:read`
- `audit:verify`
- `audit:export`

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
| `GET /api/v1/audit/events` | `audit:read` |
| `GET /api/v1/audit/integrity` | `audit:read` |
| `GET /api/v1/audit/integrity/external` | `audit:verify` |
| `GET /api/v1/audit/evidence/export` | `audit:export` |
| `GET /api/v1/audit/trust/attestation` | `audit:verify` |
| `GET /api/v1/audit/trust/keys` | `audit:verify` |
| `GET /governance/advisories` | `transaction-monitor:read` |
| `GET /governance/advisories/analytics` | `transaction-monitor:read` |
| `GET /governance/advisories/{event_id}` | `transaction-monitor:read` |
| `GET /governance/advisories/{event_id}/audit` | `transaction-monitor:read` |
| `POST /governance/advisories/{event_id}/audit` | `governance-advisory:audit:write` |

Full matrix: [Security Foundation v1](docs/security-foundation-v1.md).

## Audit Logging

FDP-16 is split into explicit production-hardening steps:

- FDP-16.1 Durable Audit Foundation: append-only platform audit writes to MongoDB plus secondary structured logs.
- FDP-16.2 Audit Read API: authenticated, authority-protected, bounded reads of durable platform audit events.
- FDP-16.3 Sensitive Read-Access Audit: best-effort audit records for selected sensitive read endpoints.
- FDP-19 Audit Integrity Foundation: application-level append-only audit hash chain, bounded integrity verification, and audit-read tracking.
- FDP-20 External Anchoring & Evidence Export: local-file external anchor publication, bounded external anchor verification, and bounded evidence export.
- FDP-21 Audit Trust Attestation Layer: derived trust assessment built on FDP-19 internal integrity and FDP-20 external anchor/export source-of-truth signals.
- FDP-23 Local-First Independent Trust Authority: a separate local service signs external audit anchor payload hashes with asymmetric key material not held by alert-service, exports public verification keys, and supports offline verification bundles.

Audit Logging v1 records security-relevant analyst write operations in `alert-service`.

Audited actions:

- `SUBMIT_ANALYST_DECISION` on `ALERT`
- `UPDATE_FRAUD_CASE` on `FRAUD_CASE`
- governance advisory human-review entries in `ml_governance_audit_events`

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

Current sinks:

- durable MongoDB records in `audit_events` through `PersistentAuditEventPublisher`
- structured SLF4J logs through `StructuredAuditEventPublisher`

Durable audit writes happen before structured log publication. If audit persistence is unavailable, write paths fail explicitly with the platform error envelope instead of silently dropping audit intent.

Platform audit writes use an insert-only repository contract and store `partition_key`, `previous_event_hash`, `event_hash`, `hash_algorithm=SHA-256`, and `schema_version`. The current partition strategy is per-service (`partition_key=source_service:alert-service`), so `previous_event_hash` is resolved inside that partition. Corrections must be represented as new audit events, not mutation of existing events.

## Audit Integrity Guarantees

FDP-19 provides application-level audit integrity support:

- durable platform audit records are inserted through append-only repository contracts; update/delete paths are not exposed for `audit_events` or `audit_chain_anchors`
- each durable audit event is linked into a per-partition SHA-256 hash chain using unique `partition_key + chain_position` and `previous_event_hash`
- multi-instance writes acquire a local Mongo partition lock before reading the head and inserting the event/anchor; transient lock conflicts use a bounded local retry, and exhausted duplicate/race conflicts fail explicitly instead of reordering events
- each inserted audit event creates a local append-only chain anchor in `audit_chain_anchors`; the local anchor stores the latest event hash, chain position, partition key, and hash algorithm
- bounded integrity verification checks event hashes, previous-hash continuity, chain-position continuity, schema version, hash algorithm, fork indicators, and latest local anchor-to-chain-head consistency
- scheduled verification is disabled by default and must be enabled with `app.audit.integrity.scheduled-verification-enabled=true`; when enabled it is read-only observability automation that records low-cardinality metrics, logs visible errors on violations, and never repairs data
- selected sensitive reads are audited separately in `read_access_audit_events` with bounded metadata only

The unique `partition_key + chain_position` constraint is enforced for positioned FDP-19 audit records. Older local development records created before `chain_position` existed may remain readable without receiving synthetic positions; new writes after such a legacy head continue at a counted next position and keep the previous-hash link.

Write-path audit atomicity is explicit: for protected analyst decision and fraud-case update actions, the durable audit write is attempted before the business repository save. If the durable audit write fails, the request fails before the business action is persisted. This is not a Mongo multi-document transaction and does not claim rollback of already written side effects outside that guarded sequence.

Platform audit event reads are available through `GET /api/v1/audit/events` and require `audit:read`, which is granted only to `FRAUD_OPS_ADMIN` by the local role model. This is an Audit Read API for durable platform write/governance audit events in `audit_events`; it does not return read-access audit events and is not itself proof that every sensitive data read was audited. Filters are exact-match only (`event_type`, `actor_id`, `resource_type`, `resource_id`) plus an inclusive timestamp window (`from`, `to`) and a bounded `limit` defaulting to 50 and capped at 100. The endpoint returns newest-first results and does not support regex, full-text search, export, aggregation, delete, or update operations. Clients MUST check `status` before interpreting `count` or `events`: `AVAILABLE` with `count=0` means a valid empty result, while `UNAVAILABLE` means audit storage could not be read and includes stable `reason_code=AUDIT_STORE_UNAVAILABLE` plus a non-sensitive message. Successful audit reads create a follow-up `READ_AUDIT_EVENTS` audit event with bounded filter/count metadata.

Audit integrity verification is available through `GET /api/v1/audit/integrity` and requires `audit:read`. The endpoint is read-only, bounded (`limit` default 100, max 10000), supports bounded `source_service=alert-service`, explicit `mode=HEAD|WINDOW|FULL_CHAIN`, and optional inclusive `from`/`to` for `WINDOW`. Default mode is `HEAD` unless a timestamp window is supplied, in which case default mode is `WINDOW`. `WINDOW` and `HEAD` can report `external_predecessor=true` without treating the first checked event as invalid; `FULL_CHAIN` reports a missing predecessor as a violation. Integrity checks are themselves audited with `VERIFY_AUDIT_INTEGRITY`. If verification exceeds its local time budget, the response is `PARTIAL` with stable reason code `INTEGRITY_VERIFICATION_TIME_BUDGET_EXCEEDED`.

FDP-20 extends tamper evidence outside the primary database boundary.
It does not create legal non-repudiation.

External audit anchoring is disabled by default with `app.audit.external-anchoring.enabled=false`. FDP-20 supports `disabled` and `local-file`; FDP-22 adds `object-store` as an object-store external anchor sink behind `ExternalAuditAnchorSink`. The legacy `external-object-store` placeholder is rejected; use `app.audit.external-anchoring.sink=object-store`. When enabled with `app.audit.external-anchoring.sink=local-file`, alert-service publishes local audit anchors to a local verification JSONL sink outside MongoDB. Publication is idempotent by `local_anchor_id`; duplicate publication does not overwrite the existing external anchor. External sink failures are logged and counted, but they do not block durable audit writes.

Local-file external anchors are development verification artifacts only.
They are not production WORM storage and are not suitable for high-volume production retention. The local-file sink reads the whole JSONL file for verification queries and is blocked in prod-like profiles (`prod`, `production`, `staging`).

### FDP-22 External Anchoring

FDP-22 provides an object-store external anchor sink for external anchor persistence outside MongoDB. New anchor objects are written under `audit-anchors/{encoded_partition_key}/{chain_position_padded}.json`, where `chain_position_padded` is a 20-digit zero-padded number and `encoded_partition_key` is deterministic base64url without padding. Legacy non-padded keys remain readable; no migration job rewrites existing objects. The bounded payload contains `local_anchor_id`, `partition_key`, `external_object_key`, `chain_position`, `event_hash`, `payload_hash`, and `created_at`; it does not include placeholder `previous_event_hash` evidence. `payload_hash` binds the canonical anchor payload to the object key and local anchor fields. The application never exposes delete/update paths for external anchors and enforces logical immutability by reading an existing object before write: identical binding is treated as idempotent, different content for the same key or conflicting local anchor binding fails with an explicit mismatch.

Object-store mode is strict. `app.audit.external-anchoring.sink=object-store` requires `bucket`, `prefix`, `region` or `endpoint`, credentials, and a configured object-store client adapter. Missing configuration fails startup; there is no silent fallback to local-file. Startup readiness checking is enabled by default with `app.audit.external-anchoring.object-store.startup-check-enabled=true`; optional probe writes require `startup-test-write-enabled=true` and write a clearly marked healthcheck object without deleting it. Publication verifies writes by reading the object back and comparing `local_anchor_id`, `chain_position`, `event_hash`, `payload_hash`, and `external_object_key`. Verification uses exact `partition_key + chain_position` lookup when available. When latest external HEAD must be discovered, object-store listing must provide continuation-token pagination and all pages must be consumed. If listing pagination is unavailable and a listing may be truncated, HEAD is treated as unknown and the sink fails explicitly with `HEAD_SCAN_PAGINATION_UNSUPPORTED` or `HEAD_SCAN_LIMIT_EXCEEDED`; it does not return a best-effort HEAD. Real S3/GCS/Azure adapters remain future deployment work.

### External Head Manifest

FDP-22 object-store mode maintains `<partition_prefix>/head.json` as an optimization for latest external HEAD lookup. The manifest is deterministic JSON with a `manifest_hash` over the manifest body without the hash field. It stores only bounded anchor metadata: partition key, latest chain position, latest local anchor id, latest external key, latest event hash, and update time.

The manifest is not a source of truth. `latest()` verifies the manifest hash and then reads the referenced anchor before trusting it. If the manifest is missing, unreadable, tampered, points to a missing anchor, or disagrees with the referenced anchor, the system records low-cardinality manifest metrics and falls back to the full paginated scan. Manifest update happens only after anchor write and read-after-write verification. Normal publish does not scan the partition; it reads the deterministic `partition_key + chain_position` object key and detects same-position conflicts from that object content. Manifest update failure leaves the anchor object valid but records `publication_status=PARTIAL`, `publication_reason=HEAD_MANIFEST_UPDATE_FAILED`, and `manifest_status=FAILED`; the manifest can be recomputed from anchors.

FDP-22 exposes `external_immutability_level=NONE|CONFIGURED|ENFORCED` on external integrity and trust attestation responses. The default is `NONE`; application configuration alone can at most describe `CONFIGURED`, and `ENFORCED` requires the object-store adapter to verify infrastructure immutability controls. No WORM or full immutability claim is valid unless `external_immutability_level=ENFORCED`. Object-store anchoring is not legal notarization and not compliance certification. FDP-22 is production-architecture-ready only when backed by a real `ObjectStoreAuditAnchorClient` and infrastructure immutability controls.

Successful object-store publication records a bounded `external_reference` containing `anchor_id`, `external_key`, `anchor_hash`, `external_hash`, and `verified_at` after read-after-write verification. Existing objects are read before write: an identical binding is idempotent, while different content for the deterministic key fails and is counted. Object-store operations use bounded timeout and retry settings (`operation-timeout`, `retry-backoff`, `max-attempts`); retry, timeout, operation failure, and tampering metrics are low-cardinality and never include bucket keys, credentials, tokens, exception messages, actor IDs, or resource IDs.

External anchor verification is available through `GET /api/v1/audit/integrity/external` and requires `audit:verify`. It is bounded (`limit` default 100, max 500), read-only, and checks latest local/external anchor consistency for bounded `source_service=alert-service`: local anchor existence, external anchor existence, `last_event_hash`, `chain_position`, `hash_algorithm`, `schema_version`, `local_anchor_id`, external key binding, payload hash binding, and verified immutability level. Missing or stale external anchors return `PARTIAL`; unavailable stores return `UNAVAILABLE`; mismatches return `INVALID`; external payload tampering is counted as `fraud_platform_audit_external_tampering_detected_total`. Verification access is audited with `VERIFY_EXTERNAL_AUDIT_INTEGRITY`.

Bounded evidence export is available through `GET /api/v1/audit/evidence/export` and requires `audit:export`; `audit:read` alone is insufficient. The request requires `from`, `to`, and `source_service`, uses inclusive timestamps, defaults `limit` to 100, and caps it at 500. The response includes safe audit event summaries, event hash, previous hash, chain position, local anchor references, external anchor references when available, signature metadata when available, `external_anchor_status`, `export_fingerprint`, explicit chain range fields (`chain_range_start`, `chain_range_end`, `partial_chain_range`, and `predecessor_hash` for partial ranges), and an `anchor_coverage` summary with `total_events`, `events_with_local_anchor`, `events_with_external_anchor`, `events_missing_external_anchor`, and `coverage_ratio`. `status=AVAILABLE` means local audit events, local anchors, and external anchors were available for the exported events. `status=PARTIAL` means the export is incomplete as an evidence package: local audit integrity may hold, but external verification is not fully possible. `status=UNAVAILABLE` means local audit events could not be read. `strict=true` rejects partial evidence packages with `409` instead of returning partial event summaries and records `export_status=REJECTED_STRICT_MODE` in the audit metadata. The endpoint applies a soft per-actor in-memory limit of five exports per minute per service instance; exceeding it returns `429`, audits the failed attempt, and increments a low-cardinality metric. In multi-instance deployments, effective evidence export rate limiting must be enforced at API gateway or shared infrastructure level. It does not provide unbounded export, full-text search, cursor pagination, aggregation, delete, or update. Export access is audited with `EXPORT_AUDIT_EVIDENCE`.

### Sensitive Evidence Surface

Evidence export may include sensitive audit metadata such as `actor_id` and `resource_id`. Protection is provided through backend-enforced `audit:export`, bounded query windows and result limits, an audit trail of export access, deterministic export fingerprinting, and per-instance rate limiting. Operators must treat exported evidence packages as sensitive even though raw payloads, tokens, stack traces, transaction payloads, customer/account/card identifiers, and advisory note bodies are excluded.

### Evidence Completeness

External anchors are required for full evidence validation. `status=AVAILABLE` means the local chain is valid for the exported records, external anchors are complete, and the evidence is internally and externally consistent. `status=PARTIAL` means evidence is incomplete and external verification is not fully possible; callers must inspect `reason_code`, `external_anchor_status`, `anchor_coverage`, and `export_fingerprint` before using the export as an evidence package. Missing or unavailable external anchors return `reason_code=EXTERNAL_ANCHORS_UNAVAILABLE`; partial external coverage returns `reason_code=EXTERNAL_ANCHOR_GAPS`. Each export audit event stores only a bounded summary of the query, returned count, export status, reason code, external anchor status, anchor coverage, and deterministic fingerprint. It does not store exported events or raw payloads.

Sensitive read-access audit is implemented separately for selected reads in `read_access_audit_events`: alert details, fraud case details, scored transaction monitor, governance advisory list, governance advisory details, governance advisory audit history, and governance advisory analytics. There is no public read-access audit query endpoint in this scope. These audit records are best-effort and do not block the read response if audit persistence fails. They store bounded metadata only: actor identity from the authenticated backend principal or `unknown` with an anomaly metric if no principal is present, endpoint category, resource type/id where applicable, page/size, canonical hashed query shape, bounded result count, outcome, correlation id, source service, and schema version. They do not store raw query params, filters, response payloads, transaction data, customer/account/card data, advisory content, full URLs, exception messages, tokens, or stack traces.

### FDP-20 Operational Guarantees

FDP-20 guarantees append-only durable audit events, local chain anchors, external tamper-evidence publication when a supported sink is enabled, bounded external verification, bounded evidence export, explicit `AVAILABLE` versus `PARTIAL` versus `UNAVAILABLE` status, strict-mode rejection of partial evidence packages, and complete bounded export audit metadata (`from`, `to`, `source_service`, `limit`, `returned_count`, `export_status`, `reason_code`, `external_anchor_status`, `anchor_coverage`, and `export_fingerprint`). Companion publication status records track bounded operational fields (`external_published`, `external_publication_status`, `external_published_at`, `external_sink_type`, `external_publish_attempts`, `manifest_status`, `last_external_publish_failure_reason`) without mutating audit events, local anchor records, or event hashes. `PARTIAL` means the anchor object was stored and verified but the head manifest update or verification failed; it is not counted as `PUBLISHED`. The publication status repository can query bounded not-yet-externalized anchors for operator visibility.

FDP-20/FDP-22 do not guarantee certified WORM storage, legal notarization, legal non-repudiation, HSM/KMS-backed signatures, SIEM integration, a regulator-ready archive, zero data exfiltration risk, or cross-instance rate limiting. Local-file sink is development-only and blocked in prod-like profiles. Object-store immutability depends on external bucket/object-store controls and must be interpreted through `external_immutability_level`; `NONE` and `CONFIGURED` are not WORM proof. Evidence export rate limiting is enforced per service instance; in multi-instance deployments, effective rate limiting must be enforced at API gateway or shared infrastructure level. FDP-20/FDP-22 provide external tamper-evidence, not external trust enforcement.

Metrics such as `fraud_platform_audit_events_persisted_total`, `fraud_platform_audit_persistence_failures_total`, `fraud_platform_audit_anchor_write_failures_total`, `fraud_platform_audit_chain_conflicts_total`, `fraud_platform_audit_read_requests_total`, `fraud_platform_audit_integrity_check_total`, `fraud_platform_audit_integrity_checks_total`, `fraud_platform_audit_integrity_violations_total`, `fraud_platform_audit_external_anchor_published_total`, `fraud_platform_audit_external_anchor_publish_failed_total`, `fraud_platform_audit_external_anchor_retry_total`, `fraud_platform_audit_external_anchor_timeout_total`, `fraud_platform_audit_external_anchor_operation_failure_total`, `fraud_platform_audit_external_tampering_detected_total`, `fraud_platform_audit_external_anchor_lag_seconds`, `fraud_platform_audit_external_anchor_head_scan_depth`, `fraud_platform_audit_external_integrity_checks_total`, `audit_signature_verification_total`, `audit_signature_policy_result_total`, `trust_authority_audit_write_total`, `fraud_platform_audit_evidence_exports_total`, `fraud_platform_audit_evidence_export_rate_limited_total`, `fraud_platform_audit_evidence_export_repeated_fingerprint_total`, `fraud_audit_integrity_check_total`, `fraud_audit_integrity_violation_total`, `fraud_audit_chain_head_hash`, `fraud_audit_last_anchor_hash`, `fraud_audit_integrity_status`, `fraud_platform_read_access_audit_events_persisted_total`, `fraud_platform_read_access_audit_persistence_failures_total`, `fraud_read_access_audit_actor_missing_total`, `fraud_internal_auth_success_total`, and `fraud_internal_auth_failure_total` are operational health signals only. The hash gauges are numeric fingerprints and are not compliance evidence.

### FDP-21 Trust Attestation

FDP-21 adds `GET /api/v1/audit/trust/attestation`, protected by `audit:verify`, as a derived trust assessment. Access is itself audited with `READ_AUDIT_TRUST_ATTESTATION` and bounded metadata only. It does not replace FDP-19 internal integrity verification, FDP-20 external anchor verification, or FDP-20 evidence export. The response reports `trust_level`, internal integrity status, external integrity status, external anchor status, `external_immutability_level`, single-head anchor coverage, latest chain head fields, an `attestation_fingerprint`, optional `attestation_signature`, `signing_key_id`, `signer_mode`, `attestation_signature_strength`, `external_trust_dependency`, and explicit limitations.

#### FDP-21 Trust Semantics

`attestation_signature_strength`, external anchor `signature_status`, external integrity `signature_verification_status`, and `external_immutability_level` are mandatory for interpreting FDP-21/FDP-23 trust. `SIGNED_BY_LOCAL_AUTHORITY` requires valid internal integrity, valid external anchor verification, `signature_verification_status=VALID`, verified Ed25519 external-anchor signature metadata, a known public key, and a signing authority other than `alert-service`. Stored signature metadata alone never upgrades trust. `SIGNED_ATTESTATION` represents stronger trust only when `attestation_signature_strength=PRODUCTION_READY`, `signer_mode` is backed by externally managed KMS/HSM signing material, and `external_immutability_level=ENFORCED`. Otherwise a signature is integrity metadata only and does not increase legal or compliance trust.

Trust levels:

- `INTERNAL_ONLY`: local application-level integrity is the only available signal.
- `PARTIAL_EXTERNAL`: an external boundary is configured or visible, but full external anchor consistency is not proven.
- `EXTERNALLY_ANCHORED`: FDP-20 external anchor verification is valid for the local head.
- `SIGNED_BY_LOCAL_AUTHORITY`: external anchor verification is valid and the external anchor payload hash has `signature_verification_status=VALID` from the separate local trust authority.
- `INDEPENDENTLY_VERIFIABLE`: reserved for offline evidence bundles that verify without alert-service, MongoDB, or an object store.
- `SIGNED_ATTESTATION`: external anchor verification is valid, `external_immutability_level=ENFORCED`, and attestation signing is production-ready.
- `UNAVAILABLE`: internal audit integrity cannot be read.

The attestation fingerprint is canonical over the full attestation context, including `source_service`, `limit`, `mode`, `signer_mode`, `signature_key_id` when present, trust status fields, `external_immutability_level`, anchor coverage, latest chain fields, external anchor reference, and limitations. Changing the query context, external immutability level, or external anchor changes the fingerprint.

Consumers must not treat a signed attestation as legal proof unless it is backed by production signing and matching operational controls outside this repository. FDP-21 is not legal notarization, not legal non-repudiation, not WORM storage, not a regulator-certified archive, and not SIEM evidence.

Examples:

- Local-dev signer: `trust_level=EXTERNALLY_ANCHORED`, `attestation_signature_strength=LOCAL_DEV`.
- Disabled signer: `trust_level=EXTERNALLY_ANCHORED`, `attestation_signature_strength=NONE`.
- Future KMS/HSM signer with verified immutable external storage: `trust_level=SIGNED_ATTESTATION`, `attestation_signature_strength=PRODUCTION_READY`, `external_immutability_level=ENFORCED`.

The FDP-21 local-dev signer provides integrity metadata only. It does not increase `trust_level`, does not provide external trust, and is not legal signing, not legal notarization, not WORM storage, not SIEM, and not KMS/HSM signing unless a real KMS/HSM adapter is explicitly integrated. `local-dev` signing is rejected in prod-like profiles; `kms-ready` requires `app.audit.trust.signing.kms-enabled=true` and still fails startup until a real adapter exists. FDP-21 does not add a second object-store, external verification implementation, or trust source.

FDP-21 relies on FDP-19/FDP-20 source-of-truth services. It does not mutate audit events, publish external anchors, export evidence, trigger alerts, enforce workflow, alter scoring, change Kafka contracts, switch models, retrain, rollback, or provide a compliance archive.

### FDP-23 Local Trust Authority

FDP-23 adds `audit-trust-authority`, a separate local-first service for signing external audit anchor payload hashes. The private signing key is owned by `audit-trust-authority`, not `alert-service`. Alert-service sends only a canonical audit anchor payload hash and bounded anchor metadata to the trust authority after external anchor persistence/verification.

The trust authority uses asymmetric Ed25519 signatures for the local implementation. It exposes `POST /api/v1/trust/sign`, `POST /api/v1/trust/verify`, `GET /api/v1/trust/keys`, `GET /api/v1/trust/audit/integrity`, and actuator health. The alert-service public key proxy is `GET /api/v1/audit/trust/keys`, protected by `audit:verify`; it returns public key metadata, including `key_fingerprint_sha256`, and never returns private keys.

Signed external anchor metadata includes `signature_status`, `signature`, `signing_key_id`, `signing_algorithm`, `signed_at`, `signing_authority`, and `signed_payload_hash`. External integrity responses additionally expose `signature_verification_status`, `signature_reason_code`, and bounded signing metadata so clients can distinguish stored signature metadata from a verified signature. `SIGNED_BY_LOCAL_AUTHORITY` requires valid internal integrity, valid external anchor verification, `signature_verification_status=VALID`, a verified Ed25519 signature, a known public key, and a signing authority other than `alert-service`. If trust authority verification is disabled, `UNSIGNED` remains valid local/external integrity but never upgrades trust beyond `EXTERNALLY_ANCHORED`. If trust authority verification is enabled and signing is not required, `UNSIGNED` or `UNAVAILABLE` downgrades external integrity to `PARTIAL`. If signing is required, `UNSIGNED` or `UNAVAILABLE` makes external integrity `INVALID`. Invalid, unknown-key, or revoked-key signatures are always `INVALID`.

Every trust-authority `/sign`, `/verify`, and audit-integrity call is synchronously audited before the response is returned. Audit write failure fails the request; signing is not best-effort and is not fire-and-forget. `local-file` audit is local/dev/test verification only. Prod-like profiles require `durable-append-only`, which persists a bounded Mongo-backed hash chain with `previous_event_hash`, `event_hash`, and monotonic `chain_position`. Audit events store bounded fields only: action, caller identity/service, purpose, payload hash, key id, result, reason code, timestamp, and hash-chain metadata. They do not store raw payloads, tokens, private keys, or secrets. `GET /api/v1/trust/audit/integrity` verifies the trust-authority audit chain and returns `VALID`, `INVALID`, or `UNAVAILABLE` with bounded violations.

The `/sign` endpoint is caller-bound and purpose-bound. Alert-service signs each trust-authority request with a per-service HMAC secret; service identity headers and `X-Internal-Trust-Request-Id` are bound into the signature and are not trusted by themselves. The default local policy allows `alert-service` to sign `AUDIT_ANCHOR` payload hashes and grants `fraud-scoring-service` no signing purpose. Unknown callers, invalid HMAC credentials, repeated request ids within the bounded TTL cache, or unauthorized purposes are rejected and audited. Prod-like profiles require an explicit caller allowlist with per-caller non-default HMAC secrets; the implicit local allowlist is rejected. Signing is rate-limited per verified service identity per minute and emits low-cardinality metrics.

The trust authority supports a minimal local key registry with `ACTIVE`, `RETIRED`, and `REVOKED` keys. Only an `ACTIVE` key signs new anchors; `RETIRED` keys verify historical signatures; `REVOKED` or unknown keys fail verification. Verification enforces `signed_at` against `valid_from` and `valid_until`. Prod-like profiles reject default local HMAC credentials, require `signing-required=true`, reject generated ephemeral signing keys, forbid inline private-key material, and require persistent key paths with non-world/group-readable private-key permissions where POSIX permissions are available. Production deployments must provide explicit externally managed private-key paths and public verification material. `identity-mode=hmac-local` is local-first and requires an explicit exception flag in prod-like profiles; `mtls-ready` and `jwt-ready` are fail-closed placeholders until implemented. Per-service HMAC is not mTLS, not channel binding, not enterprise IAM, and not a zero-replay guarantee.

Docker local runs enable local-file external anchoring and the local trust authority so the end-to-end audit anchor signing path can be exercised without cloud services. The OIDC Docker realm also includes a separate `analyst-console-e2e` public client with direct password grants enabled for shell smoke tests only; the browser client remains PKCE/browser-oriented. This e2e client is local Docker automation, not a production authentication pattern.

`tools/audit-verifier/audit-verifier.mjs` is a local offline verifier for exported evidence material and public keys. It verifies the export fingerprint, signed anchor material, key validity windows, revoked-key status, and chain continuity. Partial chain ranges require predecessor boundary proof and still downgrade to `PARTIAL_CHAIN`/`SIGNED_ANCHORS_VERIFIED`; a subset never reports `INDEPENDENTLY_VERIFIABLE`. If an evidence bundle includes optional `expected_total_events`, fewer events than expected also downgrade to `PARTIAL_CHAIN`. It does not call alert-service, MongoDB, or an object store.

FDP-23 is intentionally local-first. The demo keys and local Docker service are for local development and verification only. Production deployments must use externally managed private key material and published public verification material. FDP-23 is not KMS/HSM signing, not legal notarization, not certified WORM storage, not SIEM integration, and not a regulator-certified archive. Full independent production trust still requires real object-store adapters, externally managed keys or KMS/HSM, operational key rotation, immutable infrastructure controls, and external retention policy.

Governance advisory audit entries are separate from fraud workflow audit logs. They are persisted append-only as human review history, derive actor identity from the backend-authenticated principal, and do not affect scoring, model behavior, retraining, rollback, or fraud decisioning. Advisory lifecycle status is a read-time projection from the latest audit entry, not a persisted workflow state or automation trigger.

### Failure Semantics

Lifecycle depends on audit availability. `OPEN` means the audit source was readable and no audit events exist for the advisory. `UNKNOWN` means the system cannot determine lifecycle because audit lookup failed or audit truth is unavailable. The system never assumes `OPEN` when audit is unavailable. Advisory list responses return `status=PARTIAL` with `reason_code=AUDIT_UNAVAILABLE` when lifecycle enrichment is degraded; analytics responses expose `reason_code` for `PARTIAL` or `UNAVAILABLE` responses.

Filtering by `lifecycle_status` applies to the bounded advisory result set. It does not guarantee global completeness.

Audit analytics are derived from advisory and audit history through `GET /governance/advisories/analytics`. `advisories` means distinct `advisory_event_id` values in the bounded advisory projection window; `reviewed`, `open`, `resolved`, `unknown`, decision distribution, and lifecycle distribution all use that same population. `open` excludes `UNKNOWN`; audit degradation is counted separately in `unknown`. Time-to-first-review uses valid non-negative first audit durations only and reports `LOW_CONFIDENCE` below five samples. Analytics are read-only, bounded by `window_days` and `GOVERNANCE_AUDIT_ANALYTICS_MAX_AUDIT_EVENTS`, not persisted as aggregates, not an SLA, and do not trigger actions or influence scoring/model behavior.

The analytics API is stable. Breaking changes require a version bump. `PARTIAL` or `UNAVAILABLE` responses may include a bounded `reason_code`: `AUDIT_LIMIT_EXCEEDED`, `AUDIT_UNAVAILABLE`, or `ADVISORY_UNAVAILABLE`.

## Analytics Red Lines

Analytics:

- is NOT for alert triggering
- is NOT for SLA enforcement
- is NOT for model control
- is NOT for automation

Analytics metrics are observational only. They are not SLA signals, not alert triggers, and must not be used for automated decisions.

## Lifecycle Red Lines

**Lifecycle is a read-only projection of audit history.**

Lifecycle status:

- is NOT a workflow engine
- does NOT trigger actions
- does NOT influence scoring
- does NOT influence model behavior
- is NOT persisted as authoritative state

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

First-run rule of thumb:

- if this is a fresh clone, run `up --build`
- if containers already worked on this machine and you only changed code or want a restart, prefer targeted rebuilds or `up -d --no-build`

### Auth Modes

Two local analyst-auth modes are supported today.

#### 1. Demo Auth (default quickstart)

Start the default quickstart:

```bash
docker compose -f deployment/docker-compose.yml up --build
```

This now brings up the local monitoring stack too:

- Prometheus at `http://localhost:9090`
- Grafana at `http://localhost:3000`

If you already built the images once on this machine and only want to restart containers, you can use:

```bash
docker compose -f deployment/docker-compose.yml up -d --no-build
```

Behavior:

- uses `X-Demo-*` headers between `analyst-console-ui` and `alert-service`
- no browser login UI
- local/dev only

The Docker Compose quickstart enables demo auth for `alert-service` with:

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

#### 2. Local OIDC (Keycloak)

Start the local OIDC stack:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up --build
```

This starts:

- the full application stack
- local Keycloak for browser login
- Prometheus
- Grafana

If the images were already built locally and you only want to restart the full OIDC stack:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up -d --no-build
```

Behavior:

- real login via browser
- Keycloak at `http://localhost:8086`
- UI at `http://localhost:4173`
- Prometheus at `http://localhost:9090`
- Grafana at `http://localhost:3000`

Imported local realm:

- realm: `fraud-detection`
- client: `analyst-console-ui`

How this differs from the default quickstart:

- default `deployment/docker-compose.yml` keeps `alert-service` on demo auth
- `deployment/docker-compose.oidc.yml` adds local Keycloak and switches `alert-service` to JWT validation for analyst APIs
- `deployment/docker-compose.oidc.yml` also rebuilds `analyst-console-ui` with OIDC-specific `VITE_*` values for the Docker UI on `http://localhost:4173`

#### Local OIDC Login Flow

- click `Sign in with OIDC`
- browser redirects to Keycloak
- log in with one of the local test users
- Keycloak redirects back to `/auth/callback`
- `oidc-client-ts` completes the callback and restores the provider-backed session
- session becomes authenticated in the SPA
- frontend may normalize provider `groups` into existing UI role labels for UX, but backend JWT validation remains authoritative for RBAC
- API calls use `Authorization: Bearer <access_token>`

Text architecture diagram:

```text
Browser
  -> Keycloak (login)
  -> redirect /auth/callback
  -> SPA (oidc-client-ts)
  -> Authorization: Bearer
  -> alert-service (JWT Resource Server)
  -> RBAC enforcement
```

Local JWT wiring in OIDC mode:

- issuer visible to browser and validated by backend:
  - `http://localhost:8086/realms/fraud-detection`
- JWKS fetched by `alert-service` through Docker network:
  - `http://keycloak:8080/realms/fraud-detection/protocol/openid-connect/certs`
- backend canonical actor id claim:
  - `sub`
- backend access claim:
  - `groups`

This keeps issuer validation enabled while still letting the backend fetch keys over the Docker network.

Docker UI OIDC settings in this override:

- `VITE_AUTH_PROVIDER=oidc`
- `VITE_OIDC_AUTHORITY=http://localhost:8086/realms/fraud-detection`
- `VITE_OIDC_CLIENT_ID=analyst-console-ui`
- `VITE_OIDC_REDIRECT_URI=http://localhost:4173/auth/callback`
- `VITE_OIDC_POST_LOGOUT_REDIRECT_URI=http://localhost:4173/`
- `VITE_OIDC_SCOPE=openid profile email`

Test users:

| Username | Password | Role |
| --- | --- | --- |
| `readonly` | `readonly` | `READ_ONLY_ANALYST` |
| `analyst` | `analyst` | `ANALYST` |
| `reviewer` | `reviewer` | `REVIEWER` |
| `opsadmin` | `opsadmin` | `FRAUD_OPS_ADMIN` |

These credentials are local-only and must not be reused outside local test environments.

#### Manual verification

- login redirect works
- callback completes on `/auth/callback`
- bearer token is present in API requests
- `readonly` receives `403` on write actions
- logout works
- expired session shows the correct UI state
- Prometheus target page shows `fraud-scoring-service` and `ml-inference-service`
- Grafana contains the `FDP-5 ML Observability` dashboard

### Current OIDC limitations

- no silent refresh
- no token refresh flow
- service-to-service auth uses the internal service-auth foundation for configured ML/governance calls; local Docker may use explicit `DISABLED_LOCAL_ONLY`, the token-validator Docker override exercises compatibility `TOKEN_VALIDATOR`, `deployment/docker-compose.service-identity-rs256.yml` exercises production-target `JWT_SERVICE_IDENTITY`, and `deployment/docker-compose.service-identity-mtls.yml` exercises FDP-18 internal mTLS service identity; this is not enterprise IAM or automated certificate lifecycle management
- no production IdP config
- tokens are managed by `oidc-client-ts` for local/dev use, not as hardened production storage

Keycloak is available at:

```text
http://localhost:8086
```

### Docker First-Run Notes

For a fresh clone on a new machine:

- Docker needs internet access to pull public base images from Docker Hub and other registries during the first `up --build`
- after the first successful build, `up -d --no-build` is enough for normal local restarts

If runtime behavior does not match the current repo state after code changes, rebuild the changed services explicitly:

```bash
docker compose -f deployment/docker-compose.yml build ml-inference-service analyst-console-ui
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up -d
```

To verify the token-validator service-auth path instead of the local bypass:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.internal-auth.yml up --build -d
curl -i http://localhost:8090/governance/model
curl -i -H "X-Internal-Service-Name: alert-service" -H "X-Internal-Service-Token: local-dev-internal-token" http://localhost:8090/governance/model
curl -s http://localhost:8090/metrics | grep fraud_internal_auth
```

Expected results: the anonymous ML governance call returns `401`, the configured alert-service identity succeeds, and internal auth success/failure metrics are visible. Scoring through `fraud-scoring-service` uses the same shared token through its internal client boundary. This validates only the internal shared-secret compatibility path; use the FDP-18 override to exercise mTLS.

## RS256 Service Identity

RS256 provides service identity via signed tokens.

It does NOT provide transport-level security.

Transport security (TLS/mTLS) is still required and is outside the scope of FDP-17.

To verify the RS256 JWT service identity path:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.service-identity-rs256.yml up --build -d
curl -i http://localhost:8090/governance/advisories
curl -i -H "Authorization: Bearer invalid-token" http://localhost:8090/governance/advisories
curl -s http://localhost:8090/metrics | grep fraud_internal_auth
```

Expected results: anonymous direct ML calls return `401`, invalid bearer calls return `403`, configured Java clients attach RS256 signed JWT service identity through `InternalServiceAuthHeaders`, `ml-inference-service` validates public JWKS material only, `kid` is required, service-to-key binding is enforced, strict `iat`/`exp` freshness checks bound replay risk, and internal auth metrics remain low-cardinality. See `docs/service-identity-fdp17.md` for the full contract. This is a JWT service-auth foundation, not enterprise mTLS or enterprise IAM.

## FDP-18 mTLS Service Identity

FDP-18 adds internal mTLS service identity for configured service-to-service calls into `ml-inference-service`.

Scope:

- `fraud-scoring-service` -> `ml-inference-service` scoring with `ml-score`
- `alert-service` -> `ml-inference-service` governance reads with `governance-read`
- browser/OIDC traffic remains separate and does not use mTLS

Identity is derived from SAN URI, not CN:

- `spiffe://fraud-platform/fraud-scoring-service`
- `spiffe://fraud-platform/alert-service`

Run the local-only mTLS fixture stack with:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.service-identity-mtls.yml up --build -d
```

Expected results: direct protected ML calls without a client certificate fail, scoring through `fraud-scoring-service` succeeds, governance reads through `alert-service` succeed, wrong service authority is rejected, and internal auth metrics include `mode=MTLS_SERVICE_IDENTITY`.

Local mTLS keys under `deployment/service-identity/mtls/` are committed intentionally for local development and verification only. They must NEVER be used in any production or shared environment. Production deployments must use externally managed CA, server certificate, client certificate, and private-key material.

### Certificate Lifecycle & Operational Risk

FDP-18.1 exposes certificate lifecycle signals for internal mTLS:

- `fraud_internal_mtls_cert_expiry_seconds{source_service,target_service}`
- `fraud_internal_mtls_cert_age_seconds{source_service,target_service}`
- `fraud_internal_mtls_handshake_failures_total{reason}`
- `fraud_internal_mtls_cert_expiry_state_total{state}`

The ML server monitors its configured server certificate. Java internal clients monitor their configured client certificates and expose `mtlsCert` health with `UP`, `WARN`, `CRITICAL`, or `DOWN` state.

The system logs warnings/errors before certificate expiration, runs runtime lifecycle checks every six hours, and fails startup when a configured mTLS certificate is missing, invalid, expired, or not trusted by configured CA material. Operators must monitor expiry metrics and rotate certificates manually with overlap.

If certificates are rotated without overlap, service downtime will occur. The manual rotation flow is: generate new cert material, add new CA/trust while keeping old trust, deploy server trust update, deploy client certificate update, verify health/metrics, then remove old certificate and trust material.

FDP-18.1 does not provide automated certificate rotation, a certificate management system, CA integration, secret rotation automation, cert-manager, Vault, KMS/HSM, or external PKI automation.

FDP-18 is an internal mTLS service identity foundation. It is not enterprise IAM, not automated certificate rotation, not cert-manager/Vault/KMS integration, not full zero-trust certification, not WORM storage, and not SIEM integration. See `docs/service-identity-fdp18.md`.

## Replay Risk

JWT service identity tokens can be replayed within their validity window if intercepted.

FDP-17 reduces this risk by:

- enforcing short-lived tokens
- strict `iat` and `exp` validation
- maximum token age enforcement
- bounded clock skew tolerance
- optional in-memory replay detection

This does NOT provide:

- zero replay guarantee
- nonce-based replay prevention
- mTLS channel binding

Full replay protection requires:

- `jti` with a distributed store, OR
- mTLS with channel binding

## Local Keys

`deployment/service-identity/` contains local RS256 keys and local mTLS certificate fixtures for Docker verification.

They are committed intentionally for local development and verification only.

They must NEVER be used in any production or shared environment.

Production deployments must use externally managed private keys, JWKS material, CA material, and service certificates.

If you suspect stale local images or containers, rebuild cleanly:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml down
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up --build -d
```

### Common Local Issues

Stale Docker images:

- symptom: runtime behavior does not match current repo code
- fix:

```bash
docker compose -f deployment/docker-compose.yml build ml-inference-service
docker compose -f deployment/docker-compose.yml up -d ml-inference-service prometheus grafana
```

OIDC login loop or stale callback behavior:

- symptom: login redirects back incorrectly or UI keeps old auth settings
- fix:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml build analyst-console-ui
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up -d analyst-console-ui
```

Missing Kafka topics or broken startup ordering:

- symptom: Kafka-backed services stay unhealthy or logs show missing topic errors
- fix:

```bash
docker compose -f deployment/docker-compose.yml up -d kafka kafka-topics-init
```

Full local reset:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml down -v
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up --build -d
```

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
- `src/auth/authProvider.js` is the provider boundary for request headers and session persistence.
- `src/auth/oidcClient.js` is the SDK-facing OIDC adapter boundary.
- `src/auth/oidcSessionSource.js` is the real provider-backed session source that normalizes `profile`, `access_token`, and expiry state into the stable UI session contract.
- The UI sends auth headers from one provider-based API injection point.
- Any frontend group-to-role normalization is UX only; backend authority checks remain the enforcement contract.
- Session lifecycle states distinguish `loading`, `authenticated`, `unauthenticated`, `expired`, `access_denied`, and `auth_error`.
- OIDC mode supports login redirect, callback handling, local session bootstrap from provider-managed storage, bearer propagation, logout redirect, and expired-session UX without silent refresh.
- The Docker OIDC override rebuilds the frontend with exact callback URLs for `http://localhost:4173`.
- Write actions are disabled when the session lacks the required authority.
- HTTP 401 can render a session-required or session-expired state depending on the known lifecycle context.
- HTTP 403 shows an access-denied state.
- SessionBadge surfaces auth mode, identity, role, and authority scope.

Frontend checks are not enforcement. They keep the analyst workflow clear while backend authorization remains authoritative.

Full details: [Security Foundation v1](docs/security-foundation-v1.md).

## Path To Production

Current non-production gaps:

- Internal service-auth foundation is implemented for configured ML/governance calls through RS256 JWT service identity, FDP-18 internal mTLS service identity, compatibility token validation, and an explicit local/dev bypass mode; it is not enterprise IAM or automated certificate lifecycle management.
- The frontend still uses demo auth by default in development.
- The frontend OIDC path is a local OIDC integration and foundation for production auth, but it does not yet implement silent refresh or production deployment hardening.
- Request DTOs still accept `analystId` for compatibility, although secured write paths use the principal as actor source of truth.

Planned production path:

- Configure real issuer/JWK settings per environment.
- Configure externally managed CA, service certificates, and private-key material for FDP-18 mTLS deployments.
- Finalize IdP claim naming and group/role mapping for deployment.
- Harden the existing frontend OIDC flow for deployment environments while preserving the `userId`/`roles`/`authorities` UI contract and lifecycle states.
- Define audit retention/export policy if compliance requirements need long-term searchable audit history.

Migration notes: [Security Foundation v1](docs/security-foundation-v1.md).

## Services And Ports

| Service | Port |
| --- | --- |
| `transaction-ingest-service` | `8081` |
| `transaction-simulator-service` | `8082` |
| `feature-enricher-service` | `8083` |
| `fraud-scoring-service` | `8084` |
| `alert-service` | `8085` |
| `audit-trust-authority` | `8095` |
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
- `http://localhost:8095/actuator/health`
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
- `GET http://localhost:8085/governance/advisories`: governance advisory list with audit-derived lifecycle status.
- `GET http://localhost:8085/governance/advisories/analytics?window_days=7`: bounded read-only advisory audit analytics.
- `GET http://localhost:8085/governance/advisories/{event_id}`: single governance advisory with audit-derived lifecycle status.
- `GET http://localhost:8085/governance/advisories/{event_id}/audit`: governance advisory audit history.
- `POST http://localhost:8085/governance/advisories/{event_id}/audit`: append governance advisory human-review audit entry.
- `GET http://localhost:8085/api/v1/alerts`: alert queue.
- `GET http://localhost:8085/api/v1/alerts/{alertId}`: alert details.
- `GET http://localhost:8085/api/v1/alerts/{alertId}/assistant-summary`: assistant case summary.
- `POST http://localhost:8085/api/v1/alerts/{alertId}/decision`: submit analyst decision.
- `GET http://localhost:8085/api/v1/audit/trust/keys`: audit trust public verification keys, protected by `audit:verify`.
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
- `APP_SECURITY_JWT_ENABLED` for JWT auth path enablement
- `APP_SECURITY_JWT_JWK_SET_URI` or `APP_SECURITY_JWT_ISSUER_URI` for JWT key discovery

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
- Kafka listeners restore correlation and trace context into MDC on message-processing boundaries

Current foundation:

- `common-events` defines a shared `TraceContext`
- ingest HTTP requests populate MDC from `X-Correlation-Id`
- Kafka propagation preserves `correlationId` and forwards `traceId` when present
- listener boundaries in enrichment, scoring, and alert processing restore MDC for downstream logs

Structured logs include:

- `transactionId`
- `correlationId`
- `alertId` where applicable
- `topic` where applicable
- retry `partition`, `offset`, and `deliveryAttempt`
- audit actor/action/resource/outcome for analyst writes

## Operations And Observability

v2 is the current runtime reference; v1 is the historical baseline.

The observability docs are split into:

- `v1`: baseline metrics and triage foundation for the Java services
- `v2`: current local monitoring stack with Prometheus, Grafana, shipped alert rules, and direct ML runtime metrics

Current runtime foundation includes:

- Micrometer metrics in `alert-service`
- Micrometer metrics in `fraud-scoring-service`
- security telemetry for `401`, `403`, actor mismatch, and audit volume
- scoring and ML runtime telemetry for latency, fallback, and ML availability
- shared correlation and trace context propagation across HTTP and Kafka boundaries

Current metrics exposure:

- `alert-service`: `/actuator/metrics`, `/actuator/prometheus`
- `fraud-scoring-service`: `/actuator/metrics`, `/actuator/prometheus`
- `ml-inference-service`: `/metrics`

Reviewer-facing operations specs:

- [Operations And Observability v1](docs/operations-observability-v1.md)
- [Operations And Observability v2](docs/operations-observability-v2.md)

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

The GitHub Actions frontend job runs `npm test` before `npm run build`, so Vitest failures block UI packaging.

## ML Inference Service

`ml-inference-service` is the Python fraud model runtime used by `fraud-scoring-service`.

Runtime API:

```text
POST /v1/fraud/score
GET /health
GET /metrics
GET /governance/model
GET /governance/model/current
GET /governance/model/lifecycle
GET /governance/profile/reference
GET /governance/profile/inference
GET /governance/drift
GET /governance/drift/actions
GET /governance/advisories
GET /governance/history
```

The Java scoring service sends `MlModelInput`, where `features` is the Java-enriched feature snapshot. The Python service responds with `MlModelOutput`: fraud score, risk level, model metadata, reason codes, score details, and explanation metadata.
The ML HTTP contract is documented in `docs/openapi/ml-inference-service.openapi.yaml`; its public endpoint inventory and backward-compatibility rules are summarized in `docs/api-surface-v1.md`. ML error responses use the same platform `timestamp/status/error/message/details` envelope as the Java APIs.

Current ML and governance capabilities:

- logistic baseline model
- optional XGBoost adapter when the Python package is installed
- shared Java/Python feature contract
- training, evaluation, local registry, champion/challenger roles
- production feature training mode for inference parity
- SHADOW and COMPARE monitoring
- analyst feedback dataset support
- ML governance and drift v1 with model lineage, read-only model lifecycle visibility, synthetic/local reference profile quality, process-local inference profile lifecycle, drift confidence, advisory drift actions, governance advisory events, authenticated advisory audit history, bounded MongoDB snapshot/lifecycle/advisory history, and low-cardinality governance metrics

Governance snapshot and lifecycle persistence use the existing local MongoDB service and are optional for scoring:

| Env var | Default |
| --- | --- |
| `MONGODB_URI` | `mongodb://mongodb:27017/fraud_governance` |
| `GOVERNANCE_SNAPSHOT_COLLECTION` | `ml_governance_snapshots` |
| `GOVERNANCE_SNAPSHOT_RETENTION_LIMIT` | `500` |
| `GOVERNANCE_SNAPSHOT_INTERVAL_REQUESTS` | `50` |
| `MODEL_LIFECYCLE_COLLECTION` | `ml_model_lifecycle_events` |
| `MODEL_LIFECYCLE_RETENTION_LIMIT` | `200` |
| `GOVERNANCE_ADVISORY_COLLECTION` | `ml_governance_advisory_events` |
| `GOVERNANCE_ADVISORY_RETENTION_LIMIT` | `200` |
| `GOVERNANCE_AUDIT_HISTORY_LIMIT` | `50` |
| `GOVERNANCE_AUDIT_ANALYTICS_MAX_AUDIT_EVENTS` | `10000` |

MongoDB outage pauses persisted governance history but does not fail scoring.
Model lifecycle visibility is read-only; it does not switch models, retrain, rollback, approve models, validate model quality, or expose raw artifacts. Drift actions include lifecycle context for operator triage only and do not claim model lifecycle activity caused drift.
Governance advisory events are operator signals only; they are not fraud alerts, model actions, retraining triggers, rollback triggers, automatic decisions, or frontend workflow items. Advisory events are heuristic and may be inaccurate under low data conditions; the system does not guarantee correctness of drift or advisory signals. Advisory events include bounded confidence context and are deduplicated to avoid repeated signals from repeated polling.
Drift actions and advisory events do not block transactions, change scores, switch models, retrain models, roll back models, or trigger external alerting workflows.

FDP-12 surfaces governance advisory events in the analyst console as an operator review queue. FDP-13 adds authenticated human-review audit recording for each advisory event. FDP-14 adds advisory lifecycle badges and filtering derived only from audit history. FDP-15 adds read-only audit analytics for recent advisory handling. The UI consumes `GET /governance/advisories` through `alert-service` for advisory context plus lifecycle projection and uses `alert-service` for append-only audit history, audit analytics, and writes. Audit entries record only `decision`, optional bounded `note`, backend-derived actor, and bounded advisory metadata; lifecycle status and analytics do not change scoring, model behavior, retraining, rollback, advisory emission, or fraud decisioning. Lifecycle filtering and analytics apply only to the recent bounded advisory window.

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
- [API Surface v1](docs/api-surface-v1.md): public local HTTP endpoint inventory and backward-compatibility rules.
- [ML Inference OpenAPI](docs/openapi/ml-inference-service.openapi.yaml): current OpenAPI reference for the Python ML runtime.
- [Alert Service OpenAPI](docs/openapi/alert-service.openapi.yaml): current OpenAPI reference for platform Audit Read API and governance advisory audit endpoints.
- [API Error Contract](docs/api-error-contract.md): canonical local REST error envelope for timestamp/status/error/message/details and non-leakage rules.
- [Operations And Observability v1](docs/operations-observability-v1.md): baseline observability foundation before the local monitoring stack rollout.
- [Operations And Observability v2](docs/operations-observability-v2.md): current local Prometheus/Grafana runtime guide, ML metrics contract, alert thresholds, and troubleshooting flow.
- [ML Governance And Drift v1](docs/ml-governance-drift-v1.md): bounded runtime governance layer for active model metadata, aggregate profiles, drift status, privacy rules, and incident playbook.

TODO: If future work adds docs for data generation or deployment, keep them as a small set of consolidated feature documents instead of many prompt-sized files.

## Project Status

Implemented:

- event-driven backend flow
- Docker Compose local stack
- automatic synthetic data bootstrap
- synthetic dataset generators
- React analyst console with pagination
- read-only governance advisory review queue
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
- JWT Resource Server skeleton and JWT claim mapping into `AnalystPrincipal`
- RBAC endpoint protection
- principal-based actor identity
- audit logging v1
- local Keycloak OIDC login, callback, bearer propagation, and logout flow
- ML governance and drift v1 for `ml-inference-service`
- governance advisory human-review audit trail in `alert-service`

Known production gaps:

- Service-to-service authentication is an internal service-auth foundation with RS256 JWT service identity, JWKS public-key validation, per-service private-key signing, `kid` validation, service-to-key binding, FDP-18 internal mTLS service identity, a compatibility token-validator path, and prod-like fail-closed guards; it is not enterprise IAM, automated certificate lifecycle management, or bank-grade certification.
- mTLS is implemented only for declared internal ML scoring/governance service calls; browser/OIDC traffic, automated rotation, cert-manager, Vault/KMS, external PKI automation, and enterprise certificate lifecycle management remain out of scope.
- Durable audit storage is not WORM/immutable archive storage, legal non-repudiation, SIEM integration, long-term archival policy, regulator-ready evidence package, full HSM/KMS signing, or a final compliance archive. FDP-21 adds a derived trust attestation over FDP-19/FDP-20 signals; FDP-23 adds a local independent signing authority for external anchor hashes. Real cloud object-store adapters, certified WORM, SIEM, KMS/HSM, operational key rotation, and legal notarization remain future deployment work.
- DLT inspection/replay tooling is not implemented yet.
- The frontend defaults to demo auth in quickstart mode and supports local OIDC through the Keycloak override, but it is not a production-ready SSO setup.
- ML governance uses a synthetic/local reference profile and aggregate MongoDB snapshots; the synthetic reference is not suitable for production drift decisions and FDP-7 does not implement automatic retraining, rollback, approval UI, or production alert routing.

## Maintainer

Milosz Podsiadly  
[m.podsiadly99@gmail.com](mailto:m.podsiadly99@gmail.com)  
[GitHub - MiloszPodsiadly](https://github.com/MiloszPodsiadly)

## License

Licensed under the [MIT License](https://opensource.org/licenses/MIT).
