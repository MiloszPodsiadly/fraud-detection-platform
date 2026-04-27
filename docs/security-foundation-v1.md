# Security Foundation v1

Single technical reference for the analyst workflow security foundation in `fraud-detection-platform`.

This document consolidates RBAC, local demo auth, local OIDC, audit logging, frontend session behavior, JWT validation, and review notes into one reviewer-friendly entry point. `README.md` stays the high-level project entry point.

## Current State

Security Foundation v1 is implemented for the analyst workflow owned by `alert-service` and consumed by `analyst-console-ui`.

Backend:

- Spring Security protects analyst APIs under `/api/v1/**`.
- Spring Security also protects governance advisory audit endpoints owned by `alert-service`.
- Health and info actuator endpoints remain public for local orchestration.
- Local/demo auth is disabled by default and can only be enabled in `local`, `dev`, `docker-local`, or `test` profiles.
- JWT Resource Server can be enabled explicitly through `app.security.jwt.enabled`.
- JWT claims can be mapped into `AnalystPrincipal` through configurable claim names and role mapping.
- Authorization checks authorities, not role names.
- Write actions use authenticated principal identity as the actor source of truth.
- Audit logging v1 records analyst write actions through durable MongoDB audit records and structured logs.
- Governance advisory audit writes are append-only, authenticated human-review records.
- Demo auth is ignored when JWT auth is active.

Frontend:

- The UI uses a stable session contract: `userId`, `roles`, and `authorities`.
- The local demo session provider is isolated in `src/auth/demoSession.js`.
- Auth request header generation is now behind `src/auth/authProvider.js`.
- `src/auth/oidcClient.js` is the SDK-facing OIDC adapter boundary.
- `src/auth/oidcSessionSource.js` is now a real provider-backed session source for local Keycloak.
- Local OIDC login flow is implemented for browser login, callback handling, bearer propagation, and logout.
- Security states distinguish `loading`, `authenticated`, `unauthenticated`, `expired`, `access_denied`, `auth_error`, and missing-permission action states.
- Frontend action gating is UX only; backend authorization remains authoritative.
- A small contract guard test checks that frontend authority names stay aligned with backend `AnalystAuthority` constants.

## Scope

In scope:

- local/dev authentication path
- role and authority matrix
- endpoint authorization for analyst APIs
- 401/403 JSON error contract
- principal-based actor identity for write paths
- audit logging for write actions
- governance advisory human-review audit writes
- frontend session awareness and action gating
- migration notes for JWT/OIDC

Out of scope:

- production deployment hardening for JWT/OIDC
- shared security module extraction
- silent refresh or refresh-token-heavy frontend session manager
- mTLS service-to-service authentication

## Architecture Decision

Decision:

- Keep the authorization model inside `alert-service` for v1.
- Use roles for local personas and authorities for enforcement.
- Keep demo auth behind a local/dev adapter.
- Keep controllers and business services independent from demo header names and future identity-provider claim names.
- Use the authenticated principal as the actor source of truth when available.
- Make JWT/OIDC replace identity extraction, not endpoint authorization rules.
- OIDC replaces identity source only. Authorization RBAC, authority mapping, and endpoint protection remain unchanged.

Rationale:

- Authorities are more stable than role names for endpoint enforcement.
- A small internal principal model keeps audit, service code, and future OIDC mapping aligned.
- Local demo auth lets the UI and backend exercise real 401/403 and permission states before production auth is added.
- Keeping v1 in `alert-service` avoids premature shared-module extraction before the reusable boundaries are proven.

## Internal Security Types

Backend implementation lives under `alert-service`:

- `com.frauddetection.alert.security.authorization.AnalystAuthority`
- `com.frauddetection.alert.security.authorization.AnalystRole`
- `com.frauddetection.alert.security.principal.AnalystPrincipal`
- `com.frauddetection.alert.security.principal.CurrentAnalystUser`
- `com.frauddetection.alert.security.principal.AnalystActorResolver`
- `com.frauddetection.alert.security.auth.DemoAuthFilter`
- `com.frauddetection.alert.security.auth.JwtAnalystAuthenticationConverter`
- `com.frauddetection.alert.security.auth.JwtSecurityProperties`
- `com.frauddetection.alert.security.config.AlertSecurityConfig`
- `com.frauddetection.alert.security.config.DemoAuthSecurityConfig`
- `com.frauddetection.alert.security.config.JwtResourceServerSecurityConfig`
- `com.frauddetection.alert.audit.AuditService`
- `com.frauddetection.alert.governance.audit.GovernanceAuditController`
- `com.frauddetection.alert.governance.audit.GovernanceAuditService`

Frontend implementation:

- `analyst-console-ui/src/auth/session.js`
- `analyst-console-ui/src/auth/generatedAuthorities.js`
- `analyst-console-ui/src/auth/demoSession.js`
- `analyst-console-ui/src/auth/authProvider.js`
- `analyst-console-ui/src/auth/oidcSessionSource.js`
- `analyst-console-ui/src/auth/securityErrors.js`
- `analyst-console-ui/src/components/SessionBadge.jsx`
- `analyst-console-ui/src/components/SecurityStatePanels.jsx`

## PR Checklist

- If `AnalystAuthority` changes, `generatedAuthorities.js` must be updated.

## RBAC Model

Roles describe analyst personas. Authorities are the backend authorization contract.

### Authorities

| Authority | Capability |
| --- | --- |
| `alert:read` | List alerts and get alert details. |
| `assistant-summary:read` | Generate or read assistant case summary. |
| `alert:decision:submit` | Submit analyst decision for an alert. |
| `fraud-case:read` | List fraud cases and get case details. |
| `fraud-case:update` | Update fraud case decision/status fields. |
| `transaction-monitor:read` | Read scored transaction monitoring data. |
| `governance-advisory:audit:write` | Record human review for governance advisory events. |
| `audit:read` | Read bounded durable platform audit events. |
| `audit:verify` | Verify external audit anchor consistency. |
| `audit:export` | Export bounded audit evidence packages. |

### Roles

| Role | Intent | Authorities |
| --- | --- | --- |
| `READ_ONLY_ANALYST` | Analyst queue visibility without write actions. | `alert:read`, `assistant-summary:read`, `fraud-case:read`, `transaction-monitor:read` |
| `ANALYST` | Standard analyst who can review alerts and submit alert decisions. | `READ_ONLY_ANALYST` authorities plus `alert:decision:submit`, `governance-advisory:audit:write` |
| `REVIEWER` | Senior analyst/reviewer who can also update fraud cases. | `ANALYST` authorities plus `fraud-case:update` |
| `FRAUD_OPS_ADMIN` | Fraud operations lead/admin with full v1 access, including platform audit reads. | all v1 authorities |

### Endpoint Matrix

| Endpoint | Required Authority | Notes |
| --- | --- | --- |
| `GET /api/v1/alerts` | `alert:read` | Alert queue read. |
| `GET /api/v1/alerts/{alertId}` | `alert:read` | Alert details can contain decision history and evidence. |
| `GET /api/v1/alerts/{alertId}/assistant-summary` | `assistant-summary:read` | Separate authority because summaries may later call controlled AI infrastructure. |
| `POST /api/v1/alerts/{alertId}/decision` | `alert:decision:submit` | Write action; audit in v1. |
| `GET /api/v1/fraud-cases` | `fraud-case:read` | Case queue read. |
| `GET /api/v1/fraud-cases/{caseId}` | `fraud-case:read` | Case details read. |
| `PATCH /api/v1/fraud-cases/{caseId}` | `fraud-case:update` | Write action; audit in v1. |
| `GET /api/v1/transactions/scored` | `transaction-monitor:read` | Separate from alert read because monitor data may grow beyond alert queue use cases. |
| `GET /api/v1/audit/events` | `audit:read` | Bounded newest-first Audit Read API for durable platform audit events. Exact filters only; no export, full-text search, delete, or update. |
| `GET /api/v1/audit/integrity` | `audit:read` | Bounded read-only audit hash-chain verification. No repair, export, delete, or update. |
| `GET /api/v1/audit/integrity/external` | `audit:verify` | Bounded read-only external anchor verification. No repair, export, delete, or update. |
| `GET /api/v1/audit/evidence/export` | `audit:export` | Required-window bounded evidence export. No unbounded export, full-text search, cursor, delete, or update. |
| `GET /api/v1/audit/trust/attestation` | `audit:verify` | Bounded derived trust attestation. No raw events, raw payloads, secrets, delete, update, or unbounded export. |
| `GET /governance/advisories` | `transaction-monitor:read` | Reads governance advisory context enriched with lifecycle projection from audit history. |
| `GET /governance/advisories/analytics` | `transaction-monitor:read` | Reads derived, bounded, non-operational audit analytics. Analytics and analytics metrics are observational only and must not be used for automation, SLA enforcement, alert triggering, or model control. |
| `GET /governance/advisories/{event_id}` | `transaction-monitor:read` | Reads one governance advisory context with derived lifecycle status. |
| `GET /governance/advisories/{event_id}/audit` | `transaction-monitor:read` | Reads human-review history for governance advisory context. |
| `POST /governance/advisories/{event_id}/audit` | `governance-advisory:audit:write` | Write action; records human review only. |

## Local Demo Auth

Demo auth is a local development tool, not a production authentication path.

It requires both:

- `APP_SECURITY_DEMO_AUTH_ENABLED=true`
- active profile `local`, `dev`, `docker-local`, or `test`

If demo auth is enabled outside those profiles, `alert-service` rejects startup.

When demo auth is disabled, `X-Demo-*` headers are ignored and do not create an implicit session.

When JWT auth is enabled, demo auth is also ignored even if demo-related headers are present.

### Headers

| Header | Required | Example | Notes |
| --- | --- | --- | --- |
| `X-Demo-User-Id` | yes, to authenticate | `analyst-1` | Missing header means anonymous request. |
| `X-Demo-Roles` | no | `ANALYST` | Comma-separated `AnalystRole` names. Defaults to `READ_ONLY_ANALYST` when user id is present. |
| `X-Demo-Authorities` | no | `fraud-case:update` | Comma-separated v1 authority names for local override/testing. Unknown values are rejected. |

Example:

```bash
curl \
  -H "X-Demo-User-Id: analyst-1" \
  -H "X-Demo-Roles: ANALYST" \
  http://localhost:8085/api/v1/alerts
```

Expected behavior:

- unauthenticated requests return HTTP 401
- authenticated users without the required authority return HTTP 403
- invalid demo roles or authorities return normalized security errors
- health and info actuator endpoints remain public for local orchestration

## Actor Identity

Write request DTOs still accept `analystId` for compatibility. For secured requests, the authenticated principal wins:

- persisted analyst id uses principal user id
- emitted decision event uses principal user id
- audit actor uses principal user id and authorities
- request/principal mismatch is logged as a warning diagnostic

The request-body actor remains only as a compatibility fallback for paths without an authenticated principal, such as lower-level service tests.

## Audit Logging v1

FDP-16 is split into:

- FDP-16.1 Durable Audit Foundation: append-only platform audit writes to MongoDB plus secondary structured logs.
- FDP-16.2 Audit Read API: authenticated, `audit:read`-protected, bounded reads of durable platform audit events.
- FDP-16.3 Sensitive Read-Access Audit: best-effort audit records for selected sensitive read endpoints.
- FDP-19 Audit Integrity Foundation: application-level append-only audit hash chain, local anchors, and bounded verification.
- FDP-20 External Anchoring & Evidence Export: external anchor publication, bounded external anchor verification, and bounded evidence export.
- FDP-21 Audit Trust Attestation Layer: derived trust assessment built on FDP-19/FDP-20 source-of-truth signals.

Audit Logging v1 records security-relevant analyst write operations in `alert-service`.

Audited actions:

- `SUBMIT_ANALYST_DECISION` for `ALERT`
- `UPDATE_FRAUD_CASE` for `FRAUD_CASE`
- governance advisory human-review entries for advisory audit history

Event model:

- actor user id
- actor roles and authorities when an authenticated principal is available
- action type
- resource type
- resource id
- timestamp
- correlation id when available
- nullable bounded request id when available
- source service (`alert-service`)
- outcome: `SUCCESS`, `REJECTED`, or `FAILED`
- failure category: `NONE`, `VALIDATION`, `AUTHORIZATION`, `DEPENDENCY`, `CONFLICT`, or `UNKNOWN`
- optional bounded failure reason
- schema version (`1.0`)

Sensitive data intentionally excluded:

- analyst decision reason
- decision tags
- model feature snapshots
- transaction details
- customer data
- full request payloads

Implementation:

- Controllers do not contain audit logic.
- Protected analyst decision and fraud-case update paths call `AuditService` before the business repository save. If durable audit persistence fails, the request fails before that protected business write is persisted.
- Other non-mutating read-access audit records remain best-effort and do not block read responses.
- `AuditService` builds an `AuditEvent` from the current security principal and falls back to request actor only when no authenticated principal exists.
- `AuditEventPublisher` is the extension point.
- `PersistentAuditEventPublisher` writes append-only audit records to MongoDB collection `audit_events` through an insert-only repository contract.
- `StructuredAuditEventPublisher` writes structured key-value logs through SLF4J after durable persistence succeeds.
- Audit persistence failures surface as HTTP 503 responses on audited write paths and are not silently dropped.
- FDP-19 adds an application-level partitioned SHA-256 hash chain with `partition_key`, unique `chain_position`, `previous_event_hash`, `event_hash`, `hash_algorithm`, and `schema_version`. The current partition is `source_service:alert-service`; previous hashes are resolved within that partition. The `partition_key + chain_position` uniqueness constraint applies to positioned FDP-19 records; older local development records without `chain_position` are left immutable and new writes continue at a counted next position.
- Multi-instance writers acquire a local Mongo partition lock before reading the current chain head and inserting the audit event/local anchor. Transient lock conflicts use a bounded local retry; exhausted race conflicts fail explicitly and are counted.
- Each durable audit event insertion appends a local anchor in `audit_chain_anchors` containing the latest event hash, chain position, partition key, and hash algorithm. The anchor is application-level and local to the durable audit store.
- `GET /api/v1/audit/events` reads durable platform write/governance audit events from `audit_events` newest-first with exact-match filters only. This is an Audit Read API for that store, not a claim that every sensitive platform read was audited.
- `GET /api/v1/audit/events` does not return read-access audit events; those are stored separately in `read_access_audit_events`. A future endpoint would be needed for bounded read-access audit investigation.
- `GET /api/v1/audit/events` creates a follow-up `READ_AUDIT_EVENTS` audit event for successful reads with bounded filter/count metadata.
- `GET /api/v1/audit/integrity` requires `audit:read` and performs bounded read-only verification of event hashes, previous-hash continuity, chain-position continuity, schema version, hash algorithm, fork indicators, and latest local anchor-to-chain-head consistency. It supports `mode=HEAD|WINDOW|FULL_CHAIN`, returns `VALID`, `INVALID`, `PARTIAL`, or `UNAVAILABLE`, and never repairs or mutates audit data. `limit` defaults to `100` and is capped at `10000`; if the local verification time budget is reached the response is `PARTIAL` with reason code `INTEGRITY_VERIFICATION_TIME_BUDGET_EXCEEDED`.
- `WINDOW` and `HEAD` may report `external_predecessor=true` when the first checked event links to a predecessor outside the bounded checked set; this is not treated as a false violation. `FULL_CHAIN` detects missing predecessors.
- Scheduled integrity verification is disabled by default. When `app.audit.integrity.scheduled-verification-enabled=true`, it is read-only observability automation only: metrics/logs, no repair, no workflow, no audit mutation.
- FDP-20 extends tamper evidence outside the primary database boundary.
It does not create legal non-repudiation.
- External anchor publication is disabled by default (`app.audit.external-anchoring.enabled=false`). Supported sink names are `disabled`, `local-file`, and reserved `external-object-store`. `external-object-store` is not implemented and fails startup if selected. When enabled with `app.audit.external-anchoring.sink=local-file`, local Mongo anchors are published idempotently to a local verification JSONL file sink outside MongoDB. Publication failure is observable through logs and low-cardinality metrics and does not block durable audit writes.
- Local-file external anchors are development verification artifacts only. They are not production WORM storage, not object lock, not scalable archival storage, and are not suitable for high-volume production retention because reads scan the whole JSONL file. Prod-like profiles (`prod`, `production`, `staging`) reject local-file.
- `GET /api/v1/audit/integrity/external` requires `audit:verify`, compares bounded local/external anchor state for `source_service=alert-service`, and returns `VALID`, `INVALID`, `PARTIAL`, or `UNAVAILABLE`. Missing/stale external anchors are not hidden; mismatched hash, chain position, hash algorithm, schema version, or local anchor id are violations.
- `GET /api/v1/audit/evidence/export` requires `audit:export`; `audit:read` alone is insufficient. The endpoint requires `from`, `to`, and `source_service`, caps `limit` at `500`, returns safe audit summaries plus hash/anchor references, `external_anchor_status`, `anchor_coverage`, and a deterministic `export_fingerprint`, and audits export access. `anchor_coverage` includes total exported events, local-anchor coverage, external-anchor coverage, missing external anchors, and `coverage_ratio`. It returns `PARTIAL` when external anchors are disabled, unavailable, or incomplete. `strict=true` rejects partial evidence packages with `409`, returns no event data, and records `export_status=REJECTED_STRICT_MODE` in the export audit metadata. It applies a soft in-memory per-actor limit of five exports per minute per service instance and returns `429` on exceed. In multi-instance deployments, effective evidence export rate limiting must be enforced at API gateway or shared infrastructure level. It does not return raw payloads, tokens, stack traces, transaction payloads, customer/account/card identifiers, advisory content, or full URLs.
- Evidence completeness is explicit: `AVAILABLE` means local chain evidence and external anchors are complete for the export; `PARTIAL` means local audit evidence may be present but external verification is incomplete and callers must inspect `reason_code`, `external_anchor_status`, and `anchor_coverage`.
- Export audit events store only bounded export metadata: query window, source service, limit, returned count, export status, reason code, external anchor status, anchor coverage, and export fingerprint. They do not store the exported events themselves.
- Evidence export may include sensitive audit metadata such as `actor_id` and `resource_id`. Protection is backend-enforced `audit:export`, bounded query windows and result limits, an audit trail of export access, deterministic export fingerprinting, and per-instance rate limiting.
- Companion publication status records track external publication status fields for later operational inspection. Success records require `external_published=true`, `external_published_at`, and `external_sink_type`; failure records set `external_published=false` and may include `last_external_publish_failure_reason`. A bounded repository query can list not-yet-externalized anchors by partition for operator visibility. These fields are not part of the event hash chain and updating them does not mutate audit events or local anchor records.
- `GET /api/v1/audit/trust/attestation` requires `audit:verify` and returns only bounded status fields: `status`, `trust_level`, internal integrity status, external integrity status, external anchor status, single-head anchor coverage, latest chain head fields, latest external anchor reference, `attestation_fingerprint`, optional `attestation_signature`, `signing_key_id`, `signing_mode`, and explicit limitations.
- FDP-21 trust levels are derived only. `INTERNAL_ONLY` means local application-level integrity is the only available signal. `PARTIAL_EXTERNAL` means an external boundary is configured or visible but not fully valid. `EXTERNALLY_ANCHORED` requires FDP-20 external anchor verification to be valid. `SIGNED_ATTESTATION` requires valid external anchoring plus enabled attestation signing. `UNAVAILABLE` means internal audit integrity could not be read.
- Audit trust attestation signing is controlled by `app.audit.trust-attestation.signing.mode=disabled|local-dev|kms-ready`. `local-dev` is for development and verification only, provides integrity metadata only, and is rejected in prod-like profiles. `kms-ready` fails startup until a real KMS/HSM adapter exists; the server must not silently fall back to unsigned attestation when signing mode is enabled.
- FDP-21 verification relies on FDP-19/FDP-20 source-of-truth services. It does not implement a second external verification stack, a second evidence export, a second external anchor sink, WORM storage, SIEM integration, or legal notarization.
- Audit read filters are `event_type`, `actor_id`, `resource_type`, `resource_id`, inclusive `from`/`to` timestamps, and bounded `limit` default `50`, max `100`.
- Audit reads return `status=UNAVAILABLE`, `reason_code=AUDIT_STORE_UNAVAILABLE`, a stable non-sensitive `message`, `count=0`, and an empty event list if persistence cannot be read.
- Clients MUST check `status` before interpreting `count` or `events`; `AVAILABLE` with `count=0` is a valid empty result and is not equivalent to `UNAVAILABLE`.
- Deployments should configure bounded MongoDB driver timeouts for `alert-service` so datastore outage detection does not depend on long driver defaults; the Docker quickstart sets bounded server-selection, connect, and socket timeouts.
- Audit reads do not provide regex, free-text search, unbounded export, aggregation, delete, or update operations.
- `metadata_summary` is bounded and limited to safe correlation/request/source/schema/failure context. Raw payloads, feature vectors, tokens, secrets, stack traces, and customer/account/card data are not stored or returned.

## FDP-20 Operational Guarantees

FDP-20 guarantees append-only durable audit events, local chain anchors, external tamper-evidence publication when a supported sink is enabled, bounded external verification, bounded evidence export, explicit `AVAILABLE` versus `PARTIAL` versus `UNAVAILABLE` status, strict-mode rejection of partial evidence packages, and complete bounded export audit metadata. It also guarantees that local-file external anchoring is blocked in prod-like profiles and remains development-only.

FDP-20 does not guarantee certified WORM storage, legal notarization, legal non-repudiation, HSM/KMS-backed signatures, SIEM integration, a production object-store implementation, a regulator-ready archive, or cross-instance rate limiting. Evidence export rate limiting is enforced per service instance; in multi-instance deployments, effective rate limiting must be enforced at API gateway or shared infrastructure level. FDP-20 provides external tamper-evidence, not external trust enforcement.

Sensitive read-access audit:

- Covers `GET /api/v1/alerts/{alertId}`, `GET /api/v1/fraud-cases/{caseId}`, `GET /api/v1/transactions/scored`, `GET /governance/advisories`, `GET /governance/advisories/{eventId}`, `GET /governance/advisories/{eventId}/audit`, and `GET /governance/advisories/analytics`.
- Uses the authenticated backend principal for actor identity.
- If the backend principal is unexpectedly missing, stores `actor_id=unknown`, emits `fraud_read_access_audit_actor_missing_total{endpoint_category}`, and logs a bounded warning without URL, query, payload, or token content.
- Stores endpoint category, resource type/id where applicable, page/size, canonical hashed query shape, bounded result count, outcome, correlation id, source service, schema version, and indexed timestamps.
- Does not store raw query params, filters, response payloads, transaction data, PII/customer/account/card data, advisory content, full URLs, exception messages, tokens, or stack traces.
- Audit persistence failure does not block the sensitive read; it emits a structured warning and a low-cardinality failure metric.

Operational audit metrics:

- `fraud_platform_audit_events_persisted_total{event_type,outcome}`
- `fraud_platform_audit_persistence_failures_total{event_type}`
- `fraud_platform_audit_anchor_write_failures_total`
- `fraud_platform_audit_chain_conflicts_total`
- `fraud_platform_audit_read_requests_total{status}`
- `fraud_platform_audit_integrity_check_total{status}`
- `fraud_platform_audit_integrity_checks_total{status}`
- `fraud_platform_audit_integrity_violations_total{violation_type}`
- `fraud_platform_audit_external_anchor_published_total{sink,status}`
- `fraud_platform_audit_external_anchor_publish_failed_total{sink,reason}`
- `fraud_platform_audit_external_anchor_lag_seconds`
- `fraud_platform_audit_external_integrity_checks_total{status}`
- `fraud_platform_audit_evidence_exports_total{status}`
- `fraud_platform_audit_evidence_export_rate_limited_total`
- `fraud_platform_audit_evidence_export_repeated_fingerprint_total`
- `fraud_audit_integrity_check_total{status}`
- `fraud_audit_integrity_violation_total{violation_type}`
- `fraud_audit_chain_head_hash`
- `fraud_audit_last_anchor_hash`
- `fraud_audit_integrity_status{status}`
- `fraud_platform_read_access_audit_events_persisted_total{endpoint_category,outcome}`
- `fraud_platform_read_access_audit_persistence_failures_total{endpoint_category}`
- `fraud_read_access_audit_actor_missing_total{endpoint_category}`

These metrics are health signals, not compliance reports, and intentionally exclude actor IDs, resource IDs, audit IDs, exception messages, and other high-cardinality values.

FDP-21 does not implement enterprise WORM storage, legal timestamping, SIEM integration, automated retention, full compliance archive, HSM/KMS integration, or mTLS channel binding. Full production external trust requires real externally managed public verification material/object storage/KMS configuration and operational controls outside this code boundary.

## Internal Service Authentication

Configured internal ML scoring and governance calls use an internal service-auth foundation:

- `fraud-scoring-service` sends internal service identity when calling `ml-inference-service` scoring.
- `alert-service` sends internal service identity when calling `ml-inference-service` governance endpoints.
- `ml-inference-service` defaults to `TOKEN_VALIDATOR` compatibility mode and rejects missing internal identity with 401 when service identity is required.
- Unknown identities, invalid tokens, or missing endpoint authority return 403.
- `INTERNAL_AUTH_MODE=DISABLED_LOCAL_ONLY` is the explicit local/dev bypass mode; `LOCALDEV` remains a compatibility alias.
- `INTERNAL_AUTH_MODE=JWT_SERVICE_IDENTITY` validates signed JWT service identity. `RS256` is the production-target path: `ml-inference-service` validates public JWKS material only, requires `kid`, validates issuer, audience, expiration, required `iat`, future `iat`, maximum token age, maximum `exp - iat` TTL, signature, service identity claim, service allowlist, service-to-key binding, and required authority. Java clients attach this through `InternalServiceAuthHeaders`; business clients do not construct JWTs directly.
- JWT service tokens can still be replayed within their validity window. FDP-17 mitigates this with short TTLs, strict freshness validation, explicit clock skew bounds, and an optional bounded in-memory soft replay cache; this is not nonce-based replay prevention, mTLS channel binding, or a zero-replay guarantee.
- `INTERNAL_AUTH_MODE=MTLS_SERVICE_IDENTITY` validates certificate-backed internal service identity for FDP-18. `ml-inference-service` validates trusted client certificates, derives service identity from SAN URI values such as `spiffe://fraud-platform/fraud-scoring-service`, rejects CN-only identity, enforces the service allowlist and endpoint authority, and ignores header/JWT identity in this mode. Protected scoring and governance endpoints require mTLS identity; `/health` remains public and includes bounded `mtlsCert` certificate lifecycle state.
- HS256 remains local compatibility only and is forbidden in prod-like profiles. Startup fails if an unknown auth mode is configured, if `DISABLED_LOCAL_ONLY` is used with a prod-like profile, if a Java internal-auth client is disabled in prod-like profiles, if `JWT_SERVICE_IDENTITY` is missing issuer/audience/algorithm/key material/service authorities/service key bindings, or if `TOKEN_VALIDATOR` is used in a prod-like profile without explicit compatibility opt-in, token hash mode, and a valid allowlist.
- `INTERNAL_AUTH_TOKEN_HASH_MODE=true` lets `ml-inference-service` compare configured SHA-256 token hashes instead of storing plaintext shared secrets in its allowlist. Plain shared-secret comparison is limited to local/dev style runtime.
- `MTLS_READY` remains a fail-closed compatibility boundary. FDP-18 mTLS is internal-only. FDP-18.1 exposes certificate expiry and age metrics, logs pre-expiration warnings, and fails startup on expired configured certificates, but does not implement automated rotation, cert-manager, Vault/KMS, external PKI automation, CA integration, or enterprise certificate lifecycle management.
- Tokens are not logged, not sent to the frontend, and not used as analyst identity.
- Security events and metrics are low-cardinality and do not include tokens, actor IDs, resource IDs, paths, or exception messages.
- Internal auth metrics are `fraud_internal_auth_success_total{source_service,target_service,mode}`, `fraud_internal_auth_failure_total{target_service,mode,reason}`, `fraud_internal_mtls_handshake_failures_total{reason}`, `fraud_internal_mtls_cert_expiry_seconds{source_service,target_service}`, `fraud_internal_mtls_cert_age_seconds{source_service,target_service}`, `fraud_internal_auth_replay_rejected_total{reason}`, and `fraud_internal_auth_token_age_seconds{reason}`.

See `docs/service-identity-fdp17.md` for the FDP-17 JWT service identity contract and `docs/service-identity-fdp18.md` for the FDP-18 mTLS contract. This is an internal service-auth foundation, not enterprise IAM, not external JWKS discovery, not automated key/certificate rotation, not HSM/KMS integration, not bank-grade certification, and not a replacement for production deployment hardening.

Future sinks can be added behind `AuditEventPublisher`; they are not implemented in FDP-16:

- Kafka audit topic
- SIEM forwarder
- retention and masking policies

Governance advisory audit entries are stored separately in `ml_governance_audit_events` through the existing MongoDB infrastructure. They are append-only human-review records, not fraud decisions. The frontend may submit only `decision` and optional bounded `note`; `actor_id`, actor roles, display name, and advisory model metadata are derived server-side. Audit writes fail clearly if persistence or advisory lookup is unavailable and are never silently dropped.

Advisory lifecycle status is derived from the latest audit entry at read time. It is not persisted as an authoritative status, not trusted from the frontend, and not used to drive scoring, alerting, model lifecycle actions, or workflow automation.

## Frontend Security UX

The analyst console keeps one stable session contract and one auth provider boundary.

Current default provider behavior sends the local demo auth headers expected by `alert-service`:

- `X-Demo-User-Id`
- `X-Demo-Roles`
- `X-Demo-Authorities`

Default local session:

- user id: `analyst.local`
- role: `REVIEWER`

Optional Vite overrides:

```bash
VITE_DEMO_USER_ID=analyst-1
VITE_DEMO_ROLES=ANALYST
```

Session behavior:

- selecting `Unauthenticated` removes demo auth headers and should produce HTTP 401 states from protected API calls
- selecting `READ_ONLY_ANALYST` keeps reads enabled but disables write actions requiring `alert:decision:submit`, `fraud-case:update`, or `governance-advisory:audit:write`
- unavailable write actions show an inline permission notice with the required authority
- session lifecycle distinguishes:
  - `loading`
  - `authenticated`
  - `unauthenticated`
  - `expired`
  - `access_denied`
  - `auth_error`
- HTTP 401 can render a session-required or session-expired panel depending on known lifecycle context
- HTTP 403 shows an access-denied panel

The frontend now supports two local auth modes:

- demo auth as the default quickstart
- OIDC auth through local Keycloak when `VITE_AUTH_PROVIDER=oidc`

Current provider boundary:

- `src/auth/authProvider.js` decides how request headers are produced and how session state is persisted.
- `src/auth/demoSession.js` remains the local editable source.
- `src/auth/oidcClient.js` hides `oidc-client-ts` behind a small adapter surface.
- `src/auth/oidcSessionSource.js` normalizes a provider snapshot into UI session, token, and lifecycle state.
- bearer token propagation is active through the provider/header boundary
- `SessionBadge` can render both editable demo mode and read-only provider-driven mode, including auth mode, identity, role, authority scope, login, and logout actions.

Local OIDC browser flow:

```text
Browser
  -> Keycloak (login)
  -> redirect /auth/callback
  -> SPA (oidc-client-ts)
  -> Authorization: Bearer
  -> alert-service (JWT Resource Server)
  -> RBAC enforcement
```

Current OIDC lifecycle behavior:

- app bootstrap checks the configured provider before dashboard data loads
- callback completion hydrates the normalized session before returning to `/`
- provider `groups` may map into existing frontend role names for UX only
- backend JWT validation and authority checks remain authoritative for RBAC
- expired provider sessions render the dedicated `expired` UI state
- expired, unauthenticated, access-denied, and auth-error states block automatic dashboard fetches
- logout clears the local provider-backed session view and then redirects to IdP logout
- no silent refresh is implemented in v1
- Docker OIDC mode rebuilds `analyst-console-ui` with exact `VITE_OIDC_*` callback/logout URLs for the nginx UI on `http://localhost:4173`

## 401/403 Error Contract

Security failures use the same `ApiErrorResponse` shape as other `alert-service` API errors:

```json
{
  "timestamp": "2026-04-23T09:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication is required.",
  "details": ["reason:missing_credentials"]
}
```

Frontend handling:

- HTTP 401 means no valid session context exists.
- HTTP 403 means the session is valid but lacks the required authority.
- `details` may include one machine-readable security reason entry for lifecycle-aware clients.
- current values are:
  - `reason:missing_credentials`
  - `reason:invalid_demo_auth`
  - `reason:invalid_jwt`
  - `reason:insufficient_authority`
- The frontend should not depend on backend exception text.

## JWT And Local OIDC Implementation

Security Foundation v1 keeps local/demo authentication behind adapter boundaries. Local OIDC now provides a real browser login flow for Keycloak, while JWT validation in `alert-service` keeps the same internal principal and authority model.

Implemented backend path:

1. `spring-boot-starter-oauth2-resource-server` is added to `alert-service`.
2. JWT auth is enabled only when `app.security.jwt.enabled=true`.
3. `JwtResourceServerSecurityConfig` creates a `JwtDecoder` from `jwk-set-uri` or `issuer-uri`.
4. `JwtAnalystAuthenticationConverter` reads:
   - `app.security.jwt.user-id-claim`, default `sub`
   - `app.security.jwt.access-claim`, default `groups`
   - `app.security.jwt.role-mapping.*`
5. External access values map to internal `AnalystRole`.
6. Internal roles expand to `AnalystAuthority`.
7. `AnalystPrincipal` is placed into the Spring Security context through `AnalystAuthenticationFactory`.
8. Endpoint authorization, actor identity, and audit logging continue to operate on the same internal model.

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

Current default JWT claim mapping:

| JWT element | Default | Purpose |
| --- | --- | --- |
| user id claim | `sub` | Stable internal `AnalystPrincipal.userId` |
| access claim | `groups` | External membership / role source |
| `fraud-readonly-analyst` | `READ_ONLY_ANALYST` | read-only analyst persona |
| `fraud-analyst` | `ANALYST` | standard analyst persona |
| `fraud-reviewer` | `REVIEWER` | reviewer persona |
| `fraud-ops-admin` | `FRAUD_OPS_ADMIN` | full v1 access |

Still not implemented:

- real environment-specific deployment config for an IdP
- silent refresh / token renewal flow
- mTLS for service-to-service authentication

## Review Focus

Reviewers should check:

- endpoint matrix matches expected analyst workflow boundaries
- role-to-authority mapping is correct for all four v1 roles
- demo auth guardrails prevent accidental non-local use
- principal identity wins over payload actor fields on write paths
- audit payload excludes enough sensitive business data
- frontend action gating is clear but not treated as enforcement
- JWT/OIDC migration can reuse the current principal and authority shape

## Known Limitations

- JWT validation path exists, but no production IdP setup is shipped in this repo.
- Service-to-service authentication foundation is present for configured internal ML/governance calls.
- Internal mTLS service identity exists for configured internal ML/governance calls, but no enterprise PKI automation, cert-manager, Vault/KMS, dynamic reload, or automated rotation is shipped.
- Durable audit storage is not WORM/immutable archive storage, legal notarization, legal non-repudiation, SIEM integration, long-term archival policy, regulator-ready evidence package, or HSM/KMS signing. FDP-20 external anchoring extends tamper evidence outside the primary MongoDB boundary, but the local-file sink is not certified immutable storage and does not create legal non-repudiation.
- FDP-21 trust attestation is a derived trust assessment over FDP-19/FDP-20 signals. Local-dev signing provides integrity metadata only; it is not KMS/HSM signing, legal notarization, WORM storage, SIEM integration, or a compliance archive.
- No SIEM audit export/integration yet.
- The frontend still defaults to demo auth unless OIDC env vars are set explicitly.
- The frontend OIDC path is a local OIDC integration and foundation for production auth, not a production-ready SSO setup.
- No silent refresh or session management hardening is shipped for deployment environments yet.
- Backend and frontend currently duplicate authority names.
- `analystId` is still accepted in write DTOs for compatibility.
- `anyRequest().permitAll()` intentionally leaves non-API local routes public; protected business APIs are under `/api/v1/**`.

## Suggested Next Steps

1. Wire real JWT issuer/JWK configuration per environment and finalize deployment claim names.
2. Harden the existing frontend OIDC client path for deployment environments and finalize operational login/logout behavior.
3. Remove request-body actor fields once API compatibility allows it.
4. Define audit retention/export policy if compliance requirements need long-term searchable audit history.
5. Decide whether a shared security module is justified after another service needs the same model.
