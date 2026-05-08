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
| `PATCH` | `/api/v1/fraud-cases/{caseId}` | Updates fraud case status/assignment fields through a regulated mutation command. Non-terminal command states return operation metadata without target business fields. |
| `GET` | `/api/v1/transactions/scored` | Lists scored transaction projections. |
| `GET` | `/api/v1/audit/events` | Returns bounded newest-first durable platform audit events; requires `audit:read`. |
| `GET` | `/api/v1/audit/integrity` | Performs bounded read-only hash-chain integrity verification; requires `audit:read`. |
| `GET` | `/api/v1/audit/integrity/external` | Performs bounded read-only external anchor verification; requires `audit:verify`. |
| `GET` | `/api/v1/audit/integrity/external/coverage` | Returns bounded external anchor coverage and visible missing ranges; requires `audit:verify`. |
| `GET` | `/api/v1/audit/evidence/export` | Returns required-window bounded audit evidence export; requires `audit:export`. |
| `GET` | `/api/v1/audit/trust/attestation` | Returns a bounded derived trust attestation over FDP-19/FDP-20 signals; requires `audit:verify`. |
| `GET` | `/api/v1/audit/trust/keys` | Returns bounded public verification keys for the local trust authority; requires `audit:verify`. |
| `GET` | `/api/v1/outbox/recovery/backlog` | Returns bounded transactional outbox recovery counts; requires `outbox:inspect`. |
| `POST` | `/api/v1/outbox/recovery/run` | Runs bounded transactional outbox recovery/publish coordination; requires `outbox:recover`. |
| `POST` | `/api/v1/outbox/{eventId}/resolve-confirmation` | Resolves a transactional outbox confirmation-unknown record with `X-Idempotency-Key`, reason, and structured evidence; requires `outbox:resolve`. |
| `GET` | `/api/v1/trust/incidents` | Lists bounded open trust incidents; requires `trust-incident:read`; no payload export or delete/update path. |
| `GET` | `/api/v1/trust/incidents/signals/preview` | Previews current trust signals; requires `trust-incident:read`; rate-limited and read-access audited; does not materialize or audit a mutation. |
| `POST` | `/api/v1/trust/incidents/refresh` | Explicitly materializes current trust signals through a regulated command; requires `X-Idempotency-Key` and `trust-incident:refresh` or `trust-incident:resolve`; read endpoints do not materialize incidents. |
| `POST` | `/api/v1/trust/incidents/{incidentId}/ack` | Acknowledges an open trust incident through a regulated mutation command with `X-Idempotency-Key`; requires `trust-incident:ack`. |
| `POST` | `/api/v1/trust/incidents/{incidentId}/resolve` | Resolves or marks false-positive with reason and structured evidence; requires `trust-incident:resolve`; audited fail-closed. |
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
- Fraud-case update writes and submit-decision writes use the regulated mutation coordinator. `PATCH /api/v1/fraud-cases/{caseId}` requires `X-Idempotency-Key`, stores canonical bounded intent fields based on backend-resolved actor identity, replays matching requests, rejects conflicting key reuse with 409, and does not use the legacy `AuditMutationRecorder` path. Its response is `UpdateFraudCaseResponse`: `updated_case` is present only for committed/evidence-pending committed states; `IN_PROGRESS`, `RECOVERY_REQUIRED`, and `COMMIT_UNKNOWN` never echo the requested target status as if committed.
- `POST /api/v1/alerts/{alertId}/decision` requires `X-Idempotency-Key`, stores a `regulated_mutation_commands` record with canonical intent fields, an idempotency-key hash, and `mutation_model_version`. Null or missing model version is `LEGACY_REGULATED_MUTATION`. By default the legacy FDP-26 path is unchanged: it atomically claims command execution with a bounded lease, writes durable `ATTEMPTED` audit before business mutation, and in `app.regulated-mutations.transaction-mode=REQUIRED` commits command local state, alert business state, `transactional_outbox_records`, response snapshot, and local commit marker in one Mongo transaction. Phase audits use deterministic request ids `regulated_command_id:ATTEMPTED|SUCCESS|FAILED` so recovery can bind existing evidence without duplicating success audit events. `SUCCESS` audit, Kafka publication, external anchoring, trust-authority signature verification, and evidence confirmation are outside the legacy local ACID transaction and are coordinated idempotently. The same key and payload replay the stored response snapshot; the same key with a different payload or different backend-resolved actor returns 409; a missing key returns 400.
- FDP-29 can enable `EVIDENCE_GATED_FINALIZE_V1` for submit-decision with `app.regulated-mutations.evidence-gated-finalize.enabled=true` and `app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled=true`. This path is a local evidence-precondition-gated finalize model. It requires `app.regulated-mutations.transaction-mode=REQUIRED`, Mongo transaction capability, transactional outbox repository, outbox recovery, submit-decision recovery strategy, bounded local audit writer retry config, and required local audit-chain unique indexes at startup; otherwise it fails closed. The command moves through `EVIDENCE_PREPARING`, `EVIDENCE_PREPARED`, `FINALIZING`, and durably persists `FINALIZED_EVIDENCE_PENDING_EXTERNAL` as the local-visible state. `FINALIZED_VISIBLE` is retained only for compatibility/repair of old or interrupted states. Non-final status responses do not echo the requested decision as committed state. The local finalize transaction writes local success audit evidence through `RegulatedMutationLocalAuditPhaseWriter`, not generic `AuditService` publisher fanout. FDP-29 execution is isolated in `EvidenceGatedFinalizeExecutor`; the shared coordinator only routes by mutation model version. External witness/signature evidence confirmation remains asynchronous and can later promote to `FINALIZED_EVIDENCE_CONFIRMED`; external anchor readiness and Trust Authority signing readiness are not part of the current local finalize transaction.
- Analyst decision responses expose `operation_status`: legacy statuses plus FDP-29 statuses `EVIDENCE_PREPARING`, `EVIDENCE_PREPARED`, `FINALIZING`, `FINALIZED_VISIBLE`, `FINALIZED_EVIDENCE_PENDING_EXTERNAL`, `FINALIZED_EVIDENCE_CONFIRMED`, `REJECTED_EVIDENCE_UNAVAILABLE`, `FAILED_BUSINESS_VALIDATION`, and `FINALIZE_RECOVERY_REQUIRED`. `REJECTED_BEFORE_MUTATION` and `REJECTED_EVIDENCE_UNAVAILABLE` mean mutation was not executed. `IN_PROGRESS`, `RECOVERY_REQUIRED`, `COMMIT_UNKNOWN`, evidence-preparation, finalizing, and recovery-required statuses map to HTTP 202 because the command is accepted but not safely reportable as a stable completed result. `COMMITTED_EVIDENCE_PENDING` and `FINALIZED_EVIDENCE_PENDING_EXTERNAL` mean local visible commit and required local evidence are present while asynchronous evidence promotion remains pending. `FINALIZED_VISIBLE` should be interpreted as a compatibility/repair status, not the stable new-command result. `COMMITTED_EVIDENCE_CONFIRMED` and `FINALIZED_EVIDENCE_CONFIRMED` mean local commit marker, success audit, authoritative `PUBLISHED` transactional outbox evidence, and any configured external/signature evidence are present; they are not external WORM/notarization proof. `COMMITTED_EVIDENCE_INCOMPLETE` means the decision and outbox record committed but local success audit/evidence completion degraded; it is not a rollback and is counted by durable `audit_degradation_events` plus `fraud_platform_post_commit_audit_degraded_total`. `COMMITTED_FULLY_ANCHORED` is intentionally not exposed by the synchronous request path.
- `POST /api/v1/regulated-mutations/recover` requires `regulated-mutation:recover` and runs bounded recovery. `GET /api/v1/regulated-mutations/recovery/backlog` requires `regulated-mutation:recover` or `audit:verify` and returns bounded recovery counts by state/action. `GET /api/v1/regulated-mutations/{idempotencyKey}` remains a legacy/debug inspection path; `GET /api/v1/regulated-mutations/by-command/{commandId}` and `GET /api/v1/regulated-mutations/by-idempotency-hash/{hash}` are the preferred inspection paths. All inspection endpoints require `regulated-mutation:recover` or `audit:verify`, are rate-limited per actor/IP, fail closed with `503` when inspection access cannot be durably audited, and expose command state/evidence ids, idempotency key hash, and masked idempotency key without raw idempotency key or payload dumps. Operators must not paste raw idempotency keys in tickets, logs, runbooks, or dashboards. Recovery never re-runs business mutation after `BUSINESS_COMMITTING`; it validates reconstructed state against stored canonical intent, uncertain or unreconstructable states become `RECOVERY_REQUIRED`, and system trust degrades while recovery-required, stale-lease, committed-degraded, or repeated-failure signals exist.
- FDP-26 adds `app.regulated-mutations.transaction-mode=OFF|REQUIRED`. `OFF` preserves local/dev saga compatibility. `REQUIRED` wraps supported regulated local commit units in a Mongo transaction: command state update, business write, durable local evidence records where applicable, response snapshot, and local commit marker. Prod-like/bank startup fails unless `REQUIRED`, `app.trust-incidents.refresh-mode=ATOMIC`, a transaction manager, successful transaction capability probe, present transactional outbox repository, enabled outbox publisher/recovery, enabled outbox confirmation dual-control, and positive max attempts are configured. If Mongo transactions are unavailable in `REQUIRED` mode, the local commit fails closed instead of claiming a committed decision.
- FDP-26 trust incident refresh modes are `ATOMIC` and `PARTIAL`. `ATOMIC` is required in prod/bank/staging and means all-or-rollback refresh when transaction mode is `REQUIRED`. `PARTIAL` is local/dev/test only, is not regulator-grade, is not equivalent to `REQUIRED`, and can report `COMMITTED_DEGRADED` only after actual persisted incidents are verified from durable state.
- FDP-26 transactional outbox records are the source of truth for broker publication. The request path never publishes Kafka. The publisher claims records with bounded leases, moves them to durable `PUBLISH_ATTEMPTED` before broker publication, publishes at-least-once using stable `eventId`/`dedupeKey`/`mutationCommandId`, marks `PUBLISHED`, exposes `PUBLISH_CONFIRMATION_UNKNOWN` when broker publish may have succeeded but durable confirmation failed, and requires explicit recovery for ambiguous states. Stale `PUBLISH_ATTEMPTED` records move to confirmation-unknown, not normal retry. The old alert embedded outbox fields remain compatibility/projection fields, not the primary publication source.
- `GET /api/v1/outbox/recovery/backlog` requires `outbox:inspect`, returns bounded counts including `publish_attempted_count` and `projection_mismatch_count`, and never exposes event payloads. `POST /api/v1/outbox/recovery/run` requires `outbox:recover`, releases stale `PROCESSING` leases, moves stale `PUBLISH_ATTEMPTED` to confirmation-unknown, repairs bounded projection mismatches, and runs a bounded publish pass. `POST /api/v1/outbox/{eventId}/resolve-confirmation` requires `outbox:resolve`, `X-Idempotency-Key`, `reason`, structured evidence, and returns safe metadata only; it does not expose raw payloads, customer data, transaction payloads, decision notes, or response bodies.
- Evidence confirmation is asynchronous. Commands move from `COMMITTED_EVIDENCE_PENDING` to `COMMITTED_EVIDENCE_CONFIRMED` only after local commit marker, success audit, and authoritative published outbox evidence are present. Missing success audit becomes `COMMITTED_EVIDENCE_INCOMPLETE`/degraded; unpublished outbox evidence remains pending.
- FDP-26 is `FDP-26 - Regulated Trust Operations & Transactional Outbox Foundation`. It is a local Mongo ACID boundary plus transactional outbox delivery foundation for supported regulated mutations. `transactional_outbox_records` are authoritative; alert embedded outbox fields are projection/cache compatibility only. It is not distributed ACID across MongoDB, Kafka, trust-authority signing, and external witnesses; it is not exactly-once delivery, legal notarization, WORM storage, SIEM integration, certified archive evidence, or business rollback after a visible commit. FDP-29 adds a limited feature-flagged local evidence-precondition-gated finalize model for submit-decision only. External evidence remains asynchronous.
- Manual outbox confirmation resolution is operator-attested and not independently broker-verified. It requires `X-Idempotency-Key`, reason, actor identity, and structured evidence. In bank fail-closed mode it is dual-control: first request stores pending evidence and a distinct authenticated operator must approve/apply it. Outside bank mode the response explicitly identifies `SINGLE_CONTROL_OPERATOR_ATTESTED`.
- Evidence confirmation status is explicit: published outbox plus success audit can confirm only when configured external/signature requirements are satisfied; missing required external evidence or unknown outbox confirmation remains pending; invalid signature or terminal outbox failure degrades to committed-incomplete rather than confirmed. `/system/trust-level` exposes `transaction_mode`, `transaction_capability_status`, outbox counts, evidence confirmation pending/failed counts, and oldest pending age.

### FDP-26B / FDP-27 - Pre-Commit Finalize Model

Not implemented in FDP-26. Candidate future states: `PENDING_COMMAND`, `ATTEMPT_EVIDENCE_READY`, `COMMIT_EVIDENCE_READY`, `FINALIZING`, `COMMITTED_LOCAL`, `EVIDENCE_CONFIRMED`, and `REJECTED_EVIDENCE_UNAVAILABLE`.

### FDP-26 Trust Incident Control Plane

FDP-26 includes a minimal trust incident control plane. Signals such as terminal outbox failure, confirmation-unknown outbox state, projection mismatch, regulated mutation recovery-required, committed-degraded evidence, unresolved audit degradation, external anchor gaps, coverage unavailable, and trust-authority unavailable can create durable deduplicated incidents. Incident materialization is an explicit write path (`POST /api/v1/trust/incidents/refresh`) and uses `RegulatedMutationCoordinator` with `X-Idempotency-Key`, durable command state, coordinator-owned phase audits, response snapshot replay, and conflicting-key detection. Refresh supports `app.trust-incidents.refresh-mode=ATOMIC|PARTIAL`. In `transaction-mode=REQUIRED` or `refresh-mode=ATOMIC`, a materialization exception is rollback/failure and is never reported as `RegulatedMutationPartialCommitException`. `PARTIAL` is only for non-transactional/local compatibility and can report `COMMITTED_DEGRADED` only after actual persisted incidents are verified by active dedupe key. A clean `SUCCESS` audit is not written when materialization fails. `GET /api/v1/trust/incidents`, `GET /api/v1/trust/incidents/signals/preview`, and `GET /system/trust-level` are read-only. Signal preview is per-actor/IP rate-limited and writes a bounded read-access audit with endpoint category `PREVIEW_TRUST_INCIDENT_SIGNALS`, result count, and correlation id only; it stores no raw evidence payloads, response bodies, customer/account/card identifiers, or full URLs. In bank/prod mode, preview audit persistence failure is fail-closed and returns no preview data. Incidents support `OPEN`, `ACKNOWLEDGED`, `RESOLVED`, and `FALSE_POSITIVE`; `MITIGATING` is not an FDP-26 state. ACK means seen/owned, not mitigated; in bank fail-closed mode ACK requires a non-blank reason. ACK/RESOLVE use regulated mutation commands, require `X-Idempotency-Key`, persist bounded acknowledgement/resolution accountability fields after state save, and rely on the coordinator for phase audits. Lifecycle history is represented by regulated mutation audit events in FDP-26; a dedicated lifecycle event table is deferred. There are no delete endpoints, auto-resolve workflow, bulk actions, or hidden read-side writes.
- Scheduled verification is disabled by default and must be enabled explicitly with `app.audit.integrity.scheduled-verification-enabled=true`; when enabled it is read-only observability automation only.
- FDP-19 integrity verification is application-level evidence support. It is not WORM storage, legal notarization, legal non-repudiation, SIEM integration, long-term archival policy, regulator-ready evidence package, protection against full DB administrator rewrite, or HSM/KMS signing. FDP-20 extends tamper evidence outside the primary database boundary. It does not create legal non-repudiation.

Platform External Audit Anchor Verification API:

- `GET /api/v1/audit/integrity/external`
- Requires backend-enforced `audit:verify`.
- Query parameters: optional bounded `source_service=alert-service` and `limit` default `100`, maximum `100`.
- Compares local and external anchors for local anchor id, chain position, last event hash, hash algorithm, schema version, external reference, and external immutability level. FDP-22 object-store sinks can verify by exact `partition_key + chain_position` lookup using the deterministic encoded object key before falling back to latest-anchor comparison, and also validate `external_object_key`, `payload_hash`, `anchor_hash`, and `external_hash` binding.
- Object-store latest HEAD detection requires continuation-token pagination and consumes every page. If listing may be truncated and pagination is unavailable, verification returns a degraded `UNAVAILABLE` state instead of reporting `VALID` from a best-effort HEAD.
- External coverage accepts optional `from_position`, defaults to the latest bounded window, caps `limit` at 100, is protected by Redis-backed per-principal or per-IP request-cost limiting, and uses publication-status records rather than per-position external object reads.
- Object-store sinks may use `<partition_prefix>/head.json` as an External Head Manifest optimization. The manifest is verified by hash and by reading the referenced anchor; invalid or missing manifests are not trusted and fall back to full paginated scan. The manifest is mutable navigation metadata, not evidence; the anchor object is the evidence. Manifest status is tracked separately from external object status.
- Response status is `VALID`, `INVALID`, `PARTIAL`, `UNAVAILABLE`, or `CONFLICT`; this is structural `integrity_status`, not a trust claim by itself. Clients must also inspect `trust_level`, `signature_policy`, `signature_verification_status`, and `external_immutability_level`. Missing/stale external anchors and incompatible same-position witness records are explicit and never reported as a valid empty result.
- `trust_level` values are `LOCAL_ONLY`, `EXTERNAL_UNSIGNED`, `EXTERNAL_SIGNED`, and `EXTERNAL_INDEPENDENTLY_VERIFIED`. The current implementation returns `EXTERNAL_SIGNED` for a valid local trust-authority signature; it does not return `EXTERNAL_INDEPENDENTLY_VERIFIED` unless a real independent verifier is integrated.
- The endpoint is read-only and does not repair, mutate, delete, export, or resynchronize audit data.

Platform External Audit Anchor Sinks:

- `disabled` is the default and publishes nothing.
- `local-file` is development-only and blocked in prod-like profiles.
- `object-store` writes new anchors as `audit-anchors/{encoded_partition_key}/{chain_position_padded}.json` with 20-digit zero-padded chain positions, keeps legacy non-padded keys readable, and requires bucket, prefix, region or endpoint, credentials, client configuration, continuation-token listing for complete HEAD discovery, and startup readiness validation. Normal publish is bounded and does not list the partition; idempotency and same-position conflict detection use the deterministic object key. Real S3/GCS/Azure adapters remain future deployment work.
- The object-store payload contains original `partition_key`, `external_object_key`, `local_anchor_id`, `chain_position`, `event_hash`, `previous_event_hash`, `payload_hash`, and `created_at`; `previous_event_hash` is explicit and may be null for legacy/local anchors.
- Object-store publication is append-only at the application boundary: identical local anchor/object-key/payload binding is idempotent, different content for the same key fails, conflicting local anchor binding fails, write-after-read verification is required, and there are no delete/update paths.
- The External Head Manifest is an optimization only. It is updated after anchor verification, can be recomputed from anchors, and does not change audit payload correctness. The anchor object is evidence; the manifest is mutable navigation metadata. If manifest update fails after anchor verification, the anchor object remains `PUBLISHED` because it was written and verified by read-back, while `head_manifest_status=FAILED` and `publication_reason=HEAD_MANIFEST_UPDATE_FAILED` record the index failure. Manifest failure cannot be clean health, but it does not invalidate anchor evidence.
- Publication status distinguishes `external_object_status`, `head_manifest_status`, and `local_tracking_status`. It is the local bounded status index used by audit read/export/coverage. Missing publication status is `UNKNOWN`, never inferred as `LOCAL_ONLY` or clean `PUBLISHED`. `LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED` means a local audit event and local anchor exist while required external publication failed; coverage exposes this as `local_ahead_of_external` and `required_publication_failures`.
- Source-of-truth hierarchy: external witness object is primary evidence; publication-status repository is the local index; local audit chain is internal history; External Head Manifest is a mutable cache only.
- Default reconciliation is a bounded head-window convenience and is not proof of complete historical recovery. Old gaps require explicit bounded range reconciliation by partition and chain-position range.
- External anchoring startup validates adapter-reported witness capabilities: fake witnesses are rejected, prod-like profiles require explicit independent witness policy, timestamps must not be application-observed for proof, and `durability_guarantee` is exposed as a bounded declared capability rather than a legal/WORM claim. Regulator-grade production requires a real adapter to verify provider-side object lock or retention, versioning, overwrite denial, delete denial, witness timestamp metadata, and separate account/admin boundary; configuration-only capability claims and fake self-reporting are not production proof.
- Successful publication persists a bounded `external_reference` with `anchor_id`, `external_key`, `anchor_hash`, `external_hash`, and `verified_at`. `verified_at` is application read-back verification time, not external timestamp proof. `timestamp_value`, when present, is witness-provided timestamp metadata. `APP_OBSERVED` is weak application metadata only. Object-store operations use bounded timeout/retry settings; timeout, retry, operation failure, status-persistence failure, and tampering metrics are low-cardinality.
- New configuration should use `app.audit.external-anchoring.publication.enabled`, `publication.required`, and `publication.fail-closed`; legacy `app.audit.external-anchoring.enabled=true` is deprecated, maps to required fail-closed publication for compatibility, and emits a startup warning. `publication.enabled=true` requires a configured non-fake external sink; with `required=false` it is reconciliation/reporting only, must not block the business request path, and must be labelled `NON_GUARANTEED` in dashboards or operational summaries. Only `publication.required=true` plus `publication.fail-closed=true` is regulator-grade FDP-24 mode. `publication.required=true` means mutation audit writes emit `ATTEMPTED` before business write, emit `SUCCESS` only after business write success, and externally publish each emitted audit event before accepting the request. `publication.fail-closed=true` requires `publication.required=true`.
- In required mode, request-path audit writes fail closed if required external publication, read-back verification, mismatch/conflict handling, timestamp policy, or local publication-status persistence fails. A status persistence failure after external publication is reported with `STATUS_PERSISTENCE_FAILED_AFTER_EXTERNAL_PUBLISH` and must not be counted as clean `PUBLISHED`. Required external publication failure records `ABORTED_EXTERNAL_ANCHOR_REQUIRED` related to the affected event; audit read and evidence export expose `compensated`, `superseded_by_event_id`, backward-compatible `business_effective`, authoritative `business_effective_status`, `audit_evidence_status`, per-event `external_anchor_status`, `compensation_type`, and `related_event_id`. Clients MUST NOT infer business effectiveness from `outcome` or `compensated` alone. `ATTEMPTED` is not business-effective, `SUCCESS` means the business write committed, and `ABORTED_EXTERNAL_ANCHOR_REQUIRED` is false. Compensation may be outside the current page/window; read/export APIs enrich returned events with bounded compensation lookup.
- FDP-24 is fail-closed and semantically truthful, but not single-transaction atomic across the business DB, audit DB, and external witness. If business write fails after `ATTEMPTED`, a `FAILED` event is emitted when audit persistence remains available and is reported with `business_effective_status=FALSE`. If business write succeeds but `SUCCESS` external publication fails, the request fails/degrades, the `SUCCESS` event is evidence-compensated, coverage shows required failure, `business_effective_status` remains `TRUE`, `audit_evidence_status=ANCHOR_REQUIRED_FAILED`, and the local chain may have advanced. Operators should alert on `requiredPublicationFailures > 0` and use explicit bounded range reconciliation for older gaps.
- FDP-22 exposes `external_immutability_level=NONE|CONFIGURED|ENFORCED`. The default is `NONE`; application configuration alone does not prove immutability, and no WORM claim is valid unless the adapter verifies infrastructure controls and reports `ENFORCED`. Object-store anchoring is not legal notarization, WORM certification, compliance certification, or legal non-repudiation.

Platform Audit Evidence Export API:

- `GET /api/v1/audit/evidence/export`
- Requires backend-enforced `audit:export`; `audit:read` alone is insufficient.
- Requires inclusive ISO-8601 `from`, `to`, and bounded `source_service=alert-service`.
- `limit` defaults to `100`, maximum `500`; invalid windows or limits return the platform 400 envelope.
- Response includes safe audit event summaries, event hash, previous hash, chain position, local anchor references, external anchor references when available, signature metadata when available, `external_anchor_status`, `export_fingerprint`, and `anchor_coverage`.
- Each event includes explicit interpretation fields: `business_effective_status`, `audit_evidence_status`, `external_anchor_status`, `trust_level`, `integrity_status`, `signature_policy`, `signature_status`, `evidence_source`, and `confidence`.
- `anchor_coverage` includes `total_events`, `events_with_local_anchor`, `events_with_external_anchor`, `events_missing_external_anchor`, and `coverage_ratio`; `coverage_ratio=1.0` is required for a complete external evidence export.
- Response status is `AVAILABLE`, `PARTIAL`, or `UNAVAILABLE`. `PARTIAL` is used when external anchors are disabled, unavailable, or incomplete for exported events. Clients MUST check `status` and `anchor_coverage` before treating an export as a complete evidence package.
- `strict=true` rejects partial evidence packages with `409`, returns no event data, and records `export_status=REJECTED_STRICT_MODE` in the audit metadata.
- Repeated export attempts are softly rate-limited per authenticated actor per service instance and return `429` on exceed. In multi-instance deployments, effective evidence export rate limiting must be enforced at API gateway or shared infrastructure level.
- FDP-27 sensitive operational reads are centrally audited. In bank/prod mode, `GET /system/trust-level`, `GET /api/v1/trust/incidents`, `GET /api/v1/trust/incidents/signals/preview`, `GET /api/v1/audit/events`, `GET /api/v1/audit/evidence/export`, external audit coverage/integrity reads, regulated mutation inspection/backlog reads, outbox backlog reads, and audit degradation listing fail closed when read-audit persistence is unavailable.
- Export access audit metadata records bounded query/count/status/coverage/fingerprint details only; it does not persist exported event bodies.
- Export access creates an `EXPORT_AUDIT_EVIDENCE` audit event with bounded metadata.
- Responses include explicit chain range fields: `chain_range_start`, `chain_range_end`, `partial_chain_range`, and `predecessor_hash` for partial ranges. Offline verifiers must not treat a partial range as independently verifiable without predecessor boundary proof. If a bundle includes optional `expected_total_events`, fewer events than expected also downgrades verification to `PARTIAL_CHAIN`.
- FDP-24 wording rules forbid affirmative claims of notarization, regulator-proof evidence, independent verification, legal proof, WORM storage, immutable-forever storage, or exactly-once event delivery unless those properties are implemented and named by the relevant response fields. Use `tamper-evident`, `externally anchored`, `externally stored evidence`, `artifact-verifiable`, `at-least-once outbox delivery`, and `fail-closed configured/healthy/degraded`.
- `FraudDecisionEvent` includes `delivery=AT_LEAST_ONCE` and `dedupeKey=eventId`; consumers MUST deduplicate by event id. `PUBLISH_CONFIRMATION_UNKNOWN` means Kafka publish may have succeeded but alert-service could not durably mark the outbox row as published, so it is not retried automatically without `decision-outbox:reconcile` reconciliation. Operators may resolve an unknown confirmation as `PUBLISHED` or `RETRY_REQUESTED`; `PUBLISHED` requires structured broker-offset evidence or equivalent broker confirmation, and retry keeps the same event id and dedupe key.
- Evidence export may include sensitive audit metadata such as `actor_id` and `resource_id`; access protection relies on backend `audit:export`, bounded queries, audit trail, fingerprinting, and rate limiting.
- The endpoint does not support unbounded export, full-text search, cursor pagination, aggregation, delete, or update, and it does not return raw payloads, tokens, stack traces, transaction payloads, customer/account/card identifiers, advisory content, or full URLs.

Platform Trust Posture and Reconciliation APIs:

- `GET /system/trust-level` requires backend-enforced `audit:verify`. It returns live posture, not config-only posture, and clients must treat any unresolved degradation, pending degradation resolution, terminal/unknown outbox state, pending outbox resolution, missing range, local-status-unverified state, or required publication failure as `FDP24_DEGRADED`, not healthy.
- `GET /api/v1/audit/degradations` requires `audit:verify`; `POST /api/v1/audit/degradations/{auditId}/resolve` requires `audit-degradation:resolve`. Resolution records `ATTEMPTED` before mutation, `SUCCESS` after mutation, `FAILED` on update failure, operator identity, bounded reason, structured evidence reference, and final approval fields.
- `GET /api/v1/decision-outbox/unknown-confirmations` requires `audit:verify`; `POST /api/v1/decision-outbox/unknown-confirmations/{alertId}/resolve` requires `decision-outbox:reconcile` and `X-Idempotency-Key`. Unknown confirmation can be resolved only as `PUBLISHED` or `RETRY_REQUESTED`; `PUBLISHED` requires `BROKER_OFFSET` evidence or equivalent broker confirmation, and retry keeps the same event id and dedupe key. Resolution is routed through the FDP-25 regulated command model, so `ATTEMPTED` audit precedes state mutation and success-audit degradation is durable instead of reported as fully anchored.
- In `app.audit.bank-mode.fail-closed=true`, audit degradation and outbox resolution use dual-control: the first authenticated operator creates a pending resolution with structured evidence, and a distinct authenticated operator approves it. Pending resolution keeps trust posture degraded. If the final `SUCCESS` audit cannot be written after the state mutation committed, the API returns committed-incomplete and records a durable post-commit degradation instead of pretending the resolution is fully evidenced. Bank mode startup fails if outbox confirmation dual-control is disabled.

Platform Audit Trust Attestation API:

- `GET /api/v1/audit/trust/attestation`
- Requires backend-enforced `audit:verify`; the local role model grants it through `FRAUD_OPS_ADMIN`.
- Query parameters are bounded to optional `source_service=alert-service`, `limit` default `100`, maximum `500`, and optional `mode=HEAD`.
- Access is audited with `READ_AUDIT_TRUST_ATTESTATION`; metadata is bounded to source service, limit, trust level, integrity statuses, external anchor status, and fingerprint.
- Returns bounded status fields only: `status`, `trust_level`, internal integrity status, external integrity status, external anchor status, `external_immutability_level`, single-head anchor coverage, latest chain head fields, latest external anchor reference when present, `attestation_fingerprint`, optional `attestation_signature`, `signing_key_id`, `signer_mode`, `attestation_signature_strength`, `external_trust_dependency`, and explicit limitations.
- Trust levels are `INTERNAL_ONLY`, `PARTIAL_EXTERNAL`, `EXTERNALLY_ANCHORED`, `SIGNED_BY_LOCAL_AUTHORITY`, `INDEPENDENTLY_VERIFIABLE`, `SIGNED_ATTESTATION`, and `UNAVAILABLE`.
- `attestation_signature_strength`, external anchor `signature_status`, external integrity `signature_verification_status`, and `external_immutability_level` are mandatory for interpreting FDP-21/FDP-23 trust. `SIGNED_BY_LOCAL_AUTHORITY` requires valid internal integrity, valid external anchor verification, `signature_verification_status=VALID`, verified Ed25519 external-anchor signature metadata, a known public key, and a signing authority other than `alert-service`; stored signature metadata alone never upgrades trust. `SIGNED_ATTESTATION` represents stronger trust only when `attestation_signature_strength=PRODUCTION_READY`, `signer_mode` is backed by externally managed KMS/HSM signing material, and `external_immutability_level=ENFORCED`. Otherwise a signature is integrity metadata only and does not increase legal or compliance trust.
- `INTERNAL_ONLY` means local application-level integrity is the only available signal. `PARTIAL_EXTERNAL` means an external boundary is configured or visible but not fully valid. `EXTERNALLY_ANCHORED` requires FDP-20 external anchor verification to be valid. `INDEPENDENTLY_VERIFIABLE` is reserved for offline evidence bundles verified without alert-service, MongoDB, or an object store. `SIGNED_ATTESTATION` requires valid external anchoring, `external_immutability_level=ENFORCED`, and production-ready attestation signing; local-dev signing and mutable external storage never upgrade trust. `UNAVAILABLE` means internal audit integrity could not be read.
- The attestation fingerprint is canonical over the full attestation context, including `source_service`, `limit`, `mode`, `signer_mode`, `signature_key_id` when present, trust status fields, `external_immutability_level`, anchor coverage, latest chain fields, external anchor reference, and limitations.
- `app.audit.trust-attestation.signing.mode=disabled|local-dev|kms-ready`. `local-dev` is for local development and verification only, provides integrity metadata only, does not provide external trust, and is rejected in prod-like profiles. `kms-ready` requires `app.audit.trust.signing.kms-enabled=true` and still fails startup until a real KMS/HSM adapter is supplied.
- Consumers must not treat a signed attestation as legal proof unless it is backed by production signing and matching operational controls outside this repository. FDP-21 is not legal notarization, not legal non-repudiation, not WORM storage, not a regulator-certified archive, and not SIEM evidence.
- Examples: local-dev signer returns `EXTERNALLY_ANCHORED` with `LOCAL_DEV`; disabled signer returns `EXTERNALLY_ANCHORED` with `NONE`; a future real KMS/HSM signer can return `SIGNED_ATTESTATION` with `PRODUCTION_READY` only when external immutability is verified as `ENFORCED`.
- Verification relies on FDP-19/FDP-20 source-of-truth services; FDP-21 does not implement a second external verification stack, a second evidence export, or an object-store sink.
- This endpoint does not expose raw audit events, response bodies, raw payloads, Mongo internals, secrets, stack traces, full URLs, unbounded export, delete, update, WORM proof, SIEM evidence, legal non-repudiation, or compliance archive.

Platform Local Trust Authority API:

- `audit-trust-authority` exposes local internal endpoints `POST /api/v1/trust/sign`, `POST /api/v1/trust/verify`, public-key endpoint `GET /api/v1/trust/keys`, `GET /api/v1/trust/audit/integrity`, and `GET /api/v1/trust/audit/head`.
- Alert-service exposes `GET /api/v1/audit/trust/keys`, protected by backend `audit:verify`, as a bounded public verification key proxy. It returns `key_id`, `algorithm`, `public_key`, `key_fingerprint_sha256`, `valid_from`, `valid_until`, and `status` (`ACTIVE`, `RETIRED`, or `REVOKED`); it never returns private keys.
- Alert-service stores signed external anchor metadata with publication status and includes signature metadata in external anchor references when available. External integrity responses expose `signature_verification_status`; trust calculations require `VALID` and never rely on stored signature fields alone.
- If trust authority verification is disabled, `UNSIGNED` is acceptable for local/external integrity but never upgrades trust beyond `EXTERNALLY_ANCHORED`. If verification is enabled and signing is not required, `UNSIGNED` or `UNAVAILABLE` downgrades external integrity to `PARTIAL`. If signing is required, `UNSIGNED` or `UNAVAILABLE` makes external integrity `INVALID`. Invalid, unknown-key, and revoked-key signatures are always `INVALID`.
- The trust authority audits every `/sign`, `/verify`, audit-integrity, and audit-head call before returning, and audit write failure fails the request. `local-file` audit is local/dev/test verification only. The durable sink is `durable-hash-chain`, which persists a bounded Mongo-backed hash chain with `event_schema_version=1`, `request_id`, `previous_event_hash`, `event_hash`, and monotonic `chain_position`; it is tamper-evident but not immutable. Trust-authority audit storage labels are `LOCAL_FILE`, `DURABLE_HASH_CHAIN`, and future `EXTERNAL_WORM`; only externally enforced storage controls can justify `EXTERNAL_WORM`. Concurrent append conflicts are resolved by one fresh-head retry. `GET /api/v1/trust/audit/integrity` supports `mode=WINDOW` and `mode=FULL_CHAIN`; responses include `capability_level=INTERNAL_CRYPTOGRAPHIC_TRUST`, `tamper_detected`, `integrity_confidence`, and `trust_decision_trace`. A bounded suffix with an outside predecessor returns `PARTIAL` plus `BOUNDARY_PREDECESSOR_OUTSIDE_WINDOW`, while unknown schema versions, internal hash mismatches, or gaps are `INVALID`. `GET /api/v1/trust/audit/head` returns `status`, `source=trust-authority-audit`, `proof_type=LOCAL_HASH_CHAIN_HEAD`, `integrity_hint=LOCAL_CHAIN_ONLY`, `capability_level=INTERNAL_CRYPTOGRAPHIC_TRUST`, `chain_position`, `event_hash`, and `occurred_at` as FDP-24 input material; it does not publish or anchor externally, is not proof by itself, and is not WORM/notarization proof. Audit records contain bounded caller identity, request id, purpose, payload hash, key id, result, reason code, timestamp, schema version, and hash-chain metadata only.
- `/sign` requires service identity plus purpose authorization. Local/dev mode uses per-service HMAC-signed requests; service identity headers and `X-Internal-Trust-Request-Id` are bound into the HMAC signature and are not trusted by themselves. `JWT_SERVICE_IDENTITY` validates RS256 bearer JWT signature from configured public key or JWKS material, `kid`, issuer, audience, `iat`, `exp`, service claim, authorities, allowlist, and service-to-key binding; mutable identity headers are ignored for authenticated identity in JWT mode, and trust-authority never loads client private keys. Unauthorized purpose returns `403`, invalid credentials return `401`, repeated request id returns `409`, and per-verified-service rate limit exceed returns `429`.
- The trust authority supports a minimal local key lifecycle registry: `ACTIVE` signs new anchors, `RETIRED` verifies historical signatures, and `REVOKED` or unknown keys fail verification. Verification enforces `signed_at` within key validity windows. Prod-like profiles reject `identity-mode=hmac-local` unconditionally, require `signing-required=true`, reject generated ephemeral signing keys, forbid inline private-key material, require persistent signing key paths, and require complete `identity-mode=jwt-service-identity` configuration. `mtls-ready`, `jwt-ready`, and trust-authority `mtls-service-identity` are fail-closed placeholders until implemented. Per-service HMAC is local/dev/test only; it is not mTLS, not channel binding, not enterprise service identity, and not a zero-replay guarantee.
- Docker local runs keep alert-service external anchoring disabled by default because enabled external anchoring rejects fake publishers such as `local-file`. The local trust authority remains available for end-to-end smoke tests without cloud infrastructure. The OIDC Docker realm provides a separate `analyst-console-e2e` public client with direct password grants enabled for shell automation only; the browser client remains PKCE-oriented. This local e2e client is not a production authentication pattern.
- Production-target trust-authority deployments must use `JWT_SERVICE_IDENTITY` with externally managed JWT private keys on clients and public verification material on trust-authority, plus externally managed Ed25519 signing key paths. This is not trust-authority mTLS yet, not KMS/HSM signing, not legal notarization, not certified WORM storage, not external timestamping, not independent third-party verification, not SIEM integration, and not a regulator-certified archive.
- FDP-23 exposes audit head material for FDP-24. It does not publish or anchor it externally. There is no trust-authority external anchoring runtime flag, scheduler, or noop publisher in FDP-23; FDP-24 must fail fast unless a real external publisher is configured.

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

Filtering by `lifecycle_status` applies to the bounded advisory result set. It does not provide global completeness.

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

See `docs/api/api-error-contract.md` for field rules and non-leakage requirements.


