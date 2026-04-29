# API Surface v1

FDP-11 freezes the public HTTP API surface for local services without changing scoring, governance, security, or workflow behavior.

## Compatibility Rules

- Existing response fields keep their names, meanings, and JSON types.
- New response fields must be optional for clients.
- Existing endpoints are not wrapped in a new top-level `data` or `metadata` object unless a future versioned endpoint is introduced.
- Error responses use the platform `timestamp/status/error/message/details` envelope.
- Validation details are always returned in `details`; clients must not depend on framework exception text.
- Operational endpoints such as health and metrics remain operational contracts, not business APIs.

## ML Inference Service

Base URL in default Docker: `http://ml-inference-service:8090`. The FDP-18 mTLS override uses `https://ml-inference-service:8090`.

Internal ML scoring and governance endpoints require configured service identity in non-localdev runtime. Docker localdev may allow anonymous internal calls only when `INTERNAL_AUTH_MODE=DISABLED_LOCAL_ONLY` (`LOCALDEV` remains a compatibility alias). `TOKEN_VALIDATOR` remains a compatibility shared-token mode. `JWT_SERVICE_IDENTITY` validates RS256 signed service JWTs with public JWKS material, required `kid`, issuer, audience, expiration, future `iat`, service identity, service allowlist, service-to-key binding, and authority checks. `MTLS_SERVICE_IDENTITY` validates trusted client certificates, derives identity from SAN URI rather than CN, and enforces service authority per endpoint. FDP-18.1 adds certificate expiry/age metrics and `mtlsCert` health state; operators still rotate certificates manually. HS256 is local compatibility only and is forbidden in prod-like profiles. This is an internal service-auth foundation, not enterprise IAM or automated certificate lifecycle management.

| Method | Path | Contract |
| --- | --- | --- |
| `POST` | `/v1/fraud/score` | Scores a Java-enriched feature snapshot. Success shape is locked by `fraud_score_response.schema.json`. |
| `GET` | `/health` | Runtime health, active model identity, and bounded `mtlsCert` certificate lifecycle state. |
| `GET` | `/metrics` | Prometheus metrics. |
| `GET` | `/governance/model` | Active model metadata, reference profile summary, inference profile summary, drift summary. |
| `GET` | `/governance/model/current` | Read-only active model lifecycle metadata. |
| `GET` | `/governance/model/lifecycle` | Bounded read-only lifecycle event history. |
| `GET` | `/governance/profile/reference` | Reference profile details. |
| `GET` | `/governance/profile/inference` | Process-local aggregate inference profile. |
| `GET` | `/governance/drift` | Aggregate drift status. |
| `GET` | `/governance/drift/actions` | Advisory-only operator guidance. |
| `GET` | `/governance/advisories` | Bounded governance advisory event history. Success shape is locked by `governance_advisories_response.schema.json`. |
| `GET` | `/governance/history` | Bounded governance snapshot history. |

The ML OpenAPI reference is `docs/openapi/ml-inference-service.openapi.yaml`.
The alert-service platform/governance audit OpenAPI reference is `docs/openapi/alert-service.openapi.yaml`.

## Java Services

Transaction ingest service:

| Method | Path | Contract |
| --- | --- | --- |
| `POST` | `/api/v1/transactions` | Accepts external transaction ingestion requests. |

Transaction simulator service:

| Method | Path | Contract |
| --- | --- | --- |
| `POST` | `/api/v1/replay/start` | Starts synthetic replay. |
| `POST` | `/api/v1/replay/stop` | Stops synthetic replay. |
| `GET` | `/api/v1/replay/status` | Returns replay runtime status. |

Alert service:

| Method | Path | Contract |
| --- | --- | --- |
| `GET` | `/api/v1/alerts` | Lists alert queue entries. |
| `GET` | `/api/v1/alerts/{alertId}` | Returns one alert. |
| `GET` | `/api/v1/alerts/{alertId}/assistant-summary` | Returns read-only analyst assistant summary. |
| `POST` | `/api/v1/alerts/{alertId}/decision` | Records analyst decision. |
| `GET` | `/api/v1/fraud-cases` | Lists fraud cases. |
| `GET` | `/api/v1/fraud-cases/{caseId}` | Returns one fraud case. |
| `PATCH` | `/api/v1/fraud-cases/{caseId}` | Updates fraud case status/assignment fields. |
| `GET` | `/api/v1/transactions/scored` | Lists scored transaction projections. |
| `GET` | `/api/v1/audit/events` | Returns bounded newest-first durable platform audit events; requires `audit:read`. |
| `GET` | `/api/v1/audit/integrity` | Performs bounded read-only hash-chain integrity verification; requires `audit:read`. |
| `GET` | `/api/v1/audit/integrity/external` | Performs bounded read-only external anchor verification; requires `audit:verify`. |
| `GET` | `/api/v1/audit/evidence/export` | Returns required-window bounded audit evidence export; requires `audit:export`. |
| `GET` | `/api/v1/audit/trust/attestation` | Returns a bounded derived trust attestation over FDP-19/FDP-20 signals; requires `audit:verify`. |
| `GET` | `/api/v1/audit/trust/keys` | Returns bounded public verification keys for the local trust authority; requires `audit:verify`. |
| `GET` | `/governance/advisories` | Lists governance advisory events enriched with read-time lifecycle status. |
| `GET` | `/governance/advisories/analytics` | Returns bounded read-only audit analytics derived from advisory and audit history. |
| `GET` | `/governance/advisories/{event_id}` | Returns one governance advisory event enriched with read-time lifecycle status. |
| `GET` | `/governance/advisories/{event_id}/audit` | Returns bounded newest-first human-review audit history for one governance advisory event. |
| `POST` | `/governance/advisories/{event_id}/audit` | Appends one authenticated human-review audit entry for a governance advisory event. |

Governance advisory audit and lifecycle projection endpoints are owned by `alert-service`, not `ml-inference-service`, because lifecycle status is derived from authenticated human-review audit history. They do not mutate advisory events, scoring, model behavior, retraining, rollback, or fraud decisioning.

Platform Audit Read API:

- `GET /api/v1/audit/events`
- Requires backend-enforced `audit:read`; the local role model grants it only to `FRAUD_OPS_ADMIN`.
- Returns only durable platform write/governance audit events from `audit_events`. It does not return read-access audit records from `read_access_audit_events`.
- Audit events include a `source_service`-scoped SHA-256 hash chain: `previous_event_hash`, `event_hash`, `hash_algorithm`, and `schema_version`.
- Successful audit reads create a follow-up `READ_AUDIT_EVENTS` event with bounded filter/count metadata. If durable audit write fails after an available audit read, the request fails with the platform audit persistence error instead of returning unaudited audit data.
- Query filters are exact match only: `event_type`, `actor_id`, `resource_type`, `resource_id`.
- Timestamp filters `from` and `to` are inclusive ISO-8601 instants. If only `from` is provided, the upper bound is request time; if only `to` is provided, the lower bound is open-ended.
- `limit` defaults to `50`, maximum `100`; invalid limits or `from > to` return the platform 400 error envelope.
- Results are newest-first and bounded. The endpoint does not support regex, full-text search, unbounded export, pagination cursor, aggregation, delete, or update.
- `metadata_summary` is bounded and may include safe correlation id, request id, source service, schema version, failure category, and failure reason. It excludes raw payloads, feature vectors, tokens, secrets, stack traces, and customer/account/card data.
- If audit persistence cannot be read, the endpoint returns `status=UNAVAILABLE`, `reason_code=AUDIT_STORE_UNAVAILABLE`, a stable non-sensitive `message`, `count=0`, and an empty `events` array.
- Clients MUST check `status` before interpreting `count` or `events`; `AVAILABLE` with `count=0` is a valid empty result and is distinct from `UNAVAILABLE`.
- Runtime environments should set bounded MongoDB driver timeouts for `alert-service`; the Docker quickstart does this so store outages resolve to the `UNAVAILABLE` contract instead of relying on long driver defaults.
- This endpoint reads durable platform audit events. It is not itself proof that every sensitive data read was audited. Read-access audit is persisted separately and would need a separate bounded endpoint in a future scope.

Platform Audit Integrity API:

- `GET /api/v1/audit/integrity`
- Requires backend-enforced `audit:read`.
- Query parameters: optional inclusive ISO-8601 `from`/`to`, optional bounded `source_service=alert-service`, `mode=HEAD|WINDOW|FULL_CHAIN`, and `limit` default `100`, maximum `10000`.
- Response status is `VALID`, `INVALID`, `PARTIAL`, or `UNAVAILABLE`.
- Verification checks deterministic event hash, previous hash continuity, chain-position continuity, schema version, hash algorithm, fork indicators, and latest append-only anchor consistency. It reports bounded violation types and never mutates, repairs, exports, or deletes audit data.
- Durable audit chains are partitioned by `partition_key`; the current platform partition is `source_service:alert-service`. `partition_key + chain_position` is unique for positioned FDP-19 audit records. Older local development records created before `chain_position` existed are not silently rewritten; new writes continue at a counted next position and keep the previous-hash link.
- Multi-instance writes acquire a local Mongo partition lock before head read and event/anchor insertion; transient lock conflicts use a bounded local retry, and exhausted race conflicts fail explicitly.
- Each inserted durable audit event creates a local append-only anchor in `audit_chain_anchors` with the latest event hash, chain position, partition key, and hash algorithm. This provides application-level tamper evidence for accidental or partial tampering, but it does not protect against a full database administrator rewrite and is not external notarization.
- `WINDOW` and `HEAD` verification may return `external_predecessor=true` when the first checked event links to a predecessor outside the bounded checked set. This is not a false integrity violation. `FULL_CHAIN` treats a missing predecessor as a violation.
- A `PARTIAL` result means the bounded window was filled or the local verification time budget was reached; it should not be treated as full-chain verification. Time-budget partials use stable reason code `INTEGRITY_VERIFICATION_TIME_BUDGET_EXCEEDED`.
- Integrity checks create a follow-up `VERIFY_AUDIT_INTEGRITY` audit event with bounded metadata.
- Protected analyst decision and fraud-case update writes attempt durable audit persistence before the business repository save; audit failure fails the request before that business write is persisted.
- Scheduled verification is disabled by default and must be enabled explicitly with `app.audit.integrity.scheduled-verification-enabled=true`; when enabled it is read-only observability automation only.
- FDP-19 integrity verification is application-level evidence support. It is not WORM storage, legal notarization, legal non-repudiation, SIEM integration, long-term archival policy, regulator-ready evidence package, protection against full DB administrator rewrite, or HSM/KMS signing. FDP-20 extends tamper evidence outside the primary database boundary. It does not create legal non-repudiation.

Platform External Audit Anchor Verification API:

- `GET /api/v1/audit/integrity/external`
- Requires backend-enforced `audit:verify`.
- Query parameters: optional bounded `source_service=alert-service` and `limit` default `100`, maximum `500`.
- Compares local and external anchors for local anchor id, chain position, last event hash, hash algorithm, schema version, external reference, and external immutability level. FDP-22 object-store sinks can verify by exact `partition_key + chain_position` lookup using the deterministic encoded object key before falling back to latest-anchor comparison, and also validate `external_object_key`, `payload_hash`, `anchor_hash`, and `external_hash` binding.
- Object-store latest HEAD detection requires continuation-token pagination and consumes every page. If listing may be truncated and pagination is unavailable, verification returns a degraded `UNAVAILABLE` state instead of reporting `VALID` from a best-effort HEAD.
- Object-store sinks may use `<partition_prefix>/head.json` as an External Head Manifest optimization. The manifest is verified by hash and by reading the referenced anchor; invalid or missing manifests are not trusted and fall back to full paginated scan.
- Response status is `VALID`, `INVALID`, `PARTIAL`, or `UNAVAILABLE`; missing/stale external anchors are explicit and never reported as a valid empty result.
- The endpoint is read-only and does not repair, mutate, delete, export, or resynchronize audit data.

Platform External Audit Anchor Sinks:

- `disabled` is the default and publishes nothing.
- `local-file` is development-only and blocked in prod-like profiles.
- `object-store` writes new anchors as `audit-anchors/{encoded_partition_key}/{chain_position_padded}.json` with 20-digit zero-padded chain positions, keeps legacy non-padded keys readable, and requires bucket, prefix, region or endpoint, credentials, client configuration, continuation-token listing for complete HEAD discovery, and startup readiness validation. Normal publish is bounded and does not list the partition; idempotency and same-position conflict detection use the deterministic object key. Real S3/GCS/Azure adapters remain future deployment work.
- The object-store payload contains original `partition_key`, `external_object_key`, `local_anchor_id`, `chain_position`, `event_hash`, `payload_hash`, and `created_at`; it does not include placeholder `previous_event_hash` evidence.
- Object-store publication is append-only at the application boundary: identical local anchor/object-key/payload binding is idempotent, different content for the same key fails, conflicting local anchor binding fails, write-after-read verification is required, and there are no delete/update paths.
- The External Head Manifest is an optimization only. It is updated after anchor verification, can be recomputed from anchors, and does not change audit payload correctness. If manifest update fails after anchor verification, publication is reported as `PARTIAL` with `HEAD_MANIFEST_UPDATE_FAILED` and is not counted as `PUBLISHED`.
- Successful publication persists a bounded `external_reference` with `anchor_id`, `external_key`, `anchor_hash`, `external_hash`, and `verified_at`. Object-store operations use bounded timeout/retry settings; timeout, retry, operation failure, and tampering metrics are low-cardinality.
- FDP-22 exposes `external_immutability_level=NONE|CONFIGURED|ENFORCED`. The default is `NONE`; application configuration alone does not prove immutability, and no WORM claim is valid unless the adapter verifies infrastructure controls and reports `ENFORCED`. Object-store anchoring is not legal notarization, WORM certification, compliance certification, or legal non-repudiation.

Platform Audit Evidence Export API:

- `GET /api/v1/audit/evidence/export`
- Requires backend-enforced `audit:export`; `audit:read` alone is insufficient.
- Requires inclusive ISO-8601 `from`, `to`, and bounded `source_service=alert-service`.
- `limit` defaults to `100`, maximum `500`; invalid windows or limits return the platform 400 envelope.
- Response includes safe audit event summaries, event hash, previous hash, chain position, local anchor references, external anchor references when available, signature metadata when available, `external_anchor_status`, `export_fingerprint`, and `anchor_coverage`.
- `anchor_coverage` includes `total_events`, `events_with_local_anchor`, `events_with_external_anchor`, `events_missing_external_anchor`, and `coverage_ratio`; `coverage_ratio=1.0` is required for a complete external evidence export.
- Response status is `AVAILABLE`, `PARTIAL`, or `UNAVAILABLE`. `PARTIAL` is used when external anchors are disabled, unavailable, or incomplete for exported events. Clients MUST check `status` and `anchor_coverage` before treating an export as a complete evidence package.
- `strict=true` rejects partial evidence packages with `409`, returns no event data, and records `export_status=REJECTED_STRICT_MODE` in the audit metadata.
- Repeated export attempts are softly rate-limited per authenticated actor per service instance and return `429` on exceed. In multi-instance deployments, effective evidence export rate limiting must be enforced at API gateway or shared infrastructure level.
- Export access audit metadata records bounded query/count/status/coverage/fingerprint details only; it does not persist exported event bodies.
- Export access creates an `EXPORT_AUDIT_EVIDENCE` audit event with bounded metadata.
- Evidence export may include sensitive audit metadata such as `actor_id` and `resource_id`; access protection relies on backend `audit:export`, bounded queries, audit trail, fingerprinting, and rate limiting.
- The endpoint does not support unbounded export, full-text search, cursor pagination, aggregation, delete, or update, and it does not return raw payloads, tokens, stack traces, transaction payloads, customer/account/card identifiers, advisory content, or full URLs.

Platform Audit Trust Attestation API:

- `GET /api/v1/audit/trust/attestation`
- Requires backend-enforced `audit:verify`; the local role model grants it through `FRAUD_OPS_ADMIN`.
- Query parameters are bounded to optional `source_service=alert-service`, `limit` default `100`, maximum `500`, and optional `mode=HEAD`.
- Access is audited with `READ_AUDIT_TRUST_ATTESTATION`; metadata is bounded to source service, limit, trust level, integrity statuses, external anchor status, and fingerprint.
- Returns bounded status fields only: `status`, `trust_level`, internal integrity status, external integrity status, external anchor status, `external_immutability_level`, single-head anchor coverage, latest chain head fields, latest external anchor reference when present, `attestation_fingerprint`, optional `attestation_signature`, `signing_key_id`, `signer_mode`, `attestation_signature_strength`, `external_trust_dependency`, and explicit limitations.
- Trust levels are `INTERNAL_ONLY`, `PARTIAL_EXTERNAL`, `EXTERNALLY_ANCHORED`, `SIGNED_BY_LOCAL_AUTHORITY`, `INDEPENDENTLY_VERIFIABLE`, `SIGNED_ATTESTATION`, and `UNAVAILABLE`.
- `attestation_signature_strength`, external anchor `signature_status`, and `external_immutability_level` are mandatory for interpreting FDP-21/FDP-23 trust. `SIGNED_BY_LOCAL_AUTHORITY` requires valid internal integrity, valid external anchor verification, verified Ed25519 external-anchor signature metadata, a known public key, and a signing authority other than `alert-service`. `SIGNED_ATTESTATION` represents stronger trust only when `attestation_signature_strength=PRODUCTION_READY`, `signer_mode` is backed by externally managed KMS/HSM signing material, and `external_immutability_level=ENFORCED`. Otherwise a signature is integrity metadata only and does not increase legal or compliance trust.
- `INTERNAL_ONLY` means local application-level integrity is the only available signal. `PARTIAL_EXTERNAL` means an external boundary is configured or visible but not fully valid. `EXTERNALLY_ANCHORED` requires FDP-20 external anchor verification to be valid. `INDEPENDENTLY_VERIFIABLE` is reserved for offline evidence bundles verified without alert-service, MongoDB, or an object store. `SIGNED_ATTESTATION` requires valid external anchoring, `external_immutability_level=ENFORCED`, and production-ready attestation signing; local-dev signing and mutable external storage never upgrade trust. `UNAVAILABLE` means internal audit integrity could not be read.
- The attestation fingerprint is canonical over the full attestation context, including `source_service`, `limit`, `mode`, `signer_mode`, `signature_key_id` when present, trust status fields, `external_immutability_level`, anchor coverage, latest chain fields, external anchor reference, and limitations.
- `app.audit.trust-attestation.signing.mode=disabled|local-dev|kms-ready`. `local-dev` is for local development and verification only, provides integrity metadata only, does not provide external trust, and is rejected in prod-like profiles. `kms-ready` requires `app.audit.trust.signing.kms-enabled=true` and still fails startup until a real KMS/HSM adapter is supplied.
- Consumers must not treat a signed attestation as legal proof unless it is backed by production signing and matching operational controls outside this repository. FDP-21 is not legal notarization, not legal non-repudiation, not WORM storage, not a regulator-certified archive, and not SIEM evidence.
- Examples: local-dev signer returns `EXTERNALLY_ANCHORED` with `LOCAL_DEV`; disabled signer returns `EXTERNALLY_ANCHORED` with `NONE`; a future real KMS/HSM signer can return `SIGNED_ATTESTATION` with `PRODUCTION_READY` only when external immutability is verified as `ENFORCED`.
- Verification relies on FDP-19/FDP-20 source-of-truth services; FDP-21 does not implement a second external verification stack, a second evidence export, or an object-store sink.
- This endpoint does not expose raw audit events, response bodies, raw payloads, Mongo internals, secrets, stack traces, full URLs, unbounded export, delete, update, WORM proof, SIEM evidence, legal non-repudiation, or compliance archive.

Platform Local Trust Authority API:

- `audit-trust-authority` exposes local internal endpoints `POST /api/v1/trust/sign`, `POST /api/v1/trust/verify`, and public-key endpoint `GET /api/v1/trust/keys`.
- Alert-service exposes `GET /api/v1/audit/trust/keys`, protected by backend `audit:verify`, as a bounded public verification key proxy. It returns `key_id`, `algorithm`, `public_key`, `valid_from`, `valid_until`, and `status`; it never returns private keys.
- Alert-service stores signed external anchor metadata with publication status and includes signature metadata in external anchor references when available.
- If trust authority signing is unavailable and `app.audit.trust-authority.signing-required=false`, anchor publication can remain externally anchored with `signature_status=SIGNATURE_UNAVAILABLE`; no trust upgrade occurs. If signing is required, publication is partial with `SIGNATURE_FAILED`.
- Docker local runs enable local-file external anchoring and the local trust authority for end-to-end smoke tests without cloud infrastructure. The OIDC Docker realm provides a separate `analyst-console-e2e` public client with direct password grants enabled for shell automation only; the browser client remains PKCE-oriented. This local e2e client is not a production authentication pattern.
- The local Ed25519 implementation is for local development and verification. Production deployments must provide externally managed private key material and public verification material. This is not KMS/HSM signing, not legal notarization, not certified WORM storage, not SIEM integration, and not a regulator-certified archive.

Sensitive read-access audit:

- Implemented for `GET /api/v1/alerts/{alertId}`, `GET /api/v1/fraud-cases/{caseId}`, `GET /api/v1/transactions/scored`, `GET /governance/advisories`, `GET /governance/advisories/{event_id}`, `GET /governance/advisories/{event_id}/audit`, and `GET /governance/advisories/analytics`.
- Records authenticated backend principal identity, roles, `action=READ`, resource type/id where applicable, endpoint category, canonical hashed query shape, page/size, bounded result count, outcome, correlation id, source service, schema version, and indexed timestamps.
- If actor principal is missing, records `actor_id=unknown`, emits a low-cardinality anomaly metric, and logs a bounded warning without URL/query/payload/token content.
- Does not store raw query parameters, filters, response payloads, transaction data, customer/account/card data, advisory content, full URLs, exception messages, tokens, secrets, or stack traces.
- Audit persistence failure is best-effort for sensitive reads: the read response is not blocked, and alert-service emits a structured warning plus low-cardinality failure metric.

Platform audit read response:

```json
{
  "status": "AVAILABLE",
  "count": 1,
  "limit": 50,
  "events": [
    {
      "audit_event_id": "audit-1",
      "event_type": "SUBMIT_ANALYST_DECISION",
      "actor_id": "admin-1",
      "actor_display_name": "admin-1",
      "actor_roles": ["FRAUD_OPS_ADMIN"],
      "actor_type": "HUMAN",
      "resource_type": "ALERT",
      "resource_id": "alert-1",
      "action": "SUBMIT_ANALYST_DECISION",
      "outcome": "SUCCESS",
      "occurred_at": "2026-04-26T09:00:00Z",
      "correlation_id": "corr-1",
      "source_service": "alert-service",
      "partition_key": "source_service:alert-service",
      "chain_position": 1,
      "request_id": null,
      "metadata_summary": {
        "correlation_id": "corr-1",
        "request_id": null,
        "source_service": "alert-service",
        "schema_version": "1.0",
        "failure_category": "NONE",
        "failure_reason": null
      },
      "previous_event_hash": null,
      "event_hash": "sha256-hex",
      "hash_algorithm": "SHA-256",
      "schema_version": "1.0"
    }
  ]
}
```

Platform audit integrity response:

```json
{
  "status": "VALID",
  "checked": 100,
  "limit": 100,
  "verification_mode": "HEAD",
  "partial_window": false,
  "external_predecessor": false,
  "window_start_has_external_predecessor": false,
  "first_event_hash": "sha256-hex",
  "last_event_hash": "sha256-hex",
  "partition_key": "source_service:alert-service",
  "last_anchor_hash": "sha256-hex",
  "violations": []
}
```

Unavailable platform audit read response:

```json
{
  "status": "UNAVAILABLE",
  "reason_code": "AUDIT_STORE_UNAVAILABLE",
  "message": "Audit event store is currently unavailable.",
  "count": 0,
  "limit": 50,
  "events": []
}
```

Advisory lifecycle status is a read-time projection:

- `OPEN`: no audit events exist.
- `ACKNOWLEDGED`: latest audit decision is `ACKNOWLEDGED`.
- `NEEDS_FOLLOW_UP`: latest audit decision is `NEEDS_FOLLOW_UP`.
- `DISMISSED_AS_NOISE`: latest audit decision is `DISMISSED_AS_NOISE`.

Only the latest audit event matters. Lifecycle status is not persisted independently, is not workflow state, has no SLA, and triggers no automation.

Filtering by `lifecycle_status` applies to the bounded advisory result set. It does not guarantee global completeness.

Failure semantics:

- Lifecycle is a derived read-time projection and depends on audit availability.
- `OPEN` means no audit events exist and the audit source was readable.
- `UNKNOWN` means lifecycle cannot be determined because audit lookup failed or audit truth is unavailable.
- The system never assumes `OPEN` when audit is unavailable.
- `GET /governance/advisories` returns `status=PARTIAL` with `reason_code=AUDIT_UNAVAILABLE` when lifecycle enrichment is degraded.

Audit analytics are read-only and derived:

- `GET /governance/advisories/analytics?window_days=7`
- `window_days` defaults to `7` and is capped at `30`.
- `totals.advisories` is the number of distinct `advisory_event_id` values in the bounded advisory projection window.
- `totals.reviewed` means those advisories with at least one matching audit event; `totals.open` means zero matching audit events with audit available; `totals.resolved` groups reviewed lifecycle states; `totals.unknown` means audit truth was unavailable for lifecycle classification.
- `decision_distribution` uses the latest audit decision for reviewed advisories in that same projection window.
- `lifecycle_distribution` uses read-time lifecycle enrichment of that same projection and sums to `totals.advisories`; `UNKNOWN` is separate and never counted as `OPEN`.
- `review_timeliness` samples only valid non-negative first-review durations and reports `LOW_CONFIDENCE` when fewer than five samples exist.
- Status is `AVAILABLE` when advisory and audit sources are both readable, `PARTIAL` when one source is degraded or the audit scan limit is exceeded, and `UNAVAILABLE` when both sources are unavailable.
- Analytics operate on bounded time windows, cap audit scans with `GOVERNANCE_AUDIT_ANALYTICS_MAX_AUDIT_EVENTS`, and do not guarantee global completeness.
- Analytics do not persist aggregates, enforce SLA, trigger actions, or change scoring/model behavior.

### API Stability

`GET /governance/advisories/analytics` is considered stable. Breaking changes to existing fields, enums, or meanings require a version bump. The optional `reason_code` field may appear only for `PARTIAL` or `UNAVAILABLE` responses and is limited to `AUDIT_LIMIT_EXCEEDED`, `AUDIT_UNAVAILABLE`, or `ADVISORY_UNAVAILABLE`.

Advisory list filters:

- `severity`
- `model_version`
- `lifecycle_status`
- `limit`

POST request:

```json
{
  "decision": "ACKNOWLEDGED",
  "note": "Reviewed by operator"
}
```

Allowed `decision` values:

- `ACKNOWLEDGED`
- `NEEDS_FOLLOW_UP`
- `DISMISSED_AS_NOISE`

Frontend-provided `actor_id`, actor roles, or model metadata fields are rejected or ignored by contract; actor attribution is backend-derived. `note` is optional and capped at 500 characters.

GET response:

```json
{
  "advisory_event_id": "event-1",
  "status": "AVAILABLE",
  "audit_events": []
}
```

`GET` may return `status=UNAVAILABLE` with an empty `audit_events` array when audit storage is unavailable. `POST` fails clearly with the platform error envelope when persistence or advisory lookup is unavailable; explicit operator write intent is not silently dropped.

## Error Contract

All normalized local API errors use:

```json
{
  "timestamp": "2026-04-25T15:42:10.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Malformed JSON request.",
  "details": []
}
```

See `docs/api-error-contract.md` for field rules and non-leakage requirements.
