# Write-Action Audit Outbox

Status: FDP-122 infrastructure foundation.

## Scope

The alert-service-owned write-action audit outbox supports bounded business writes that must not return success unless
the business record and durable audit intent are both persisted. Governance obligations also expose a narrowly scoped
operator recovery path after bounded publication attempts are exhausted.

There is no general outbox API or unrestricted admin UI. Recovery controls are limited to the authenticated Security /
Operations workspace and dedicated audit-recovery authority. This is not a Kafka outbox, dataset export, ML evaluation,
or training-label pipeline. It is not a replacement for the existing decision event outbox in
`transactional_outbox_records`. The existing decision outbox remains owned by submit-decision publication and recovery.

## Model

Write-action audit intents are stored in `write_action_audit_outbox`.

Each record contains:

- `outboxId`
- `idempotencyKey`
- `contractVersion`
- `action`
- `resourceType`
- `resourceId`
- `correlationId`
- `actor`
- `outcome`
- `metadataSummary`
- `status`
- `attemptCount`
- `maxAttempts`
- `nextAttemptAt`
- `createdAt`
- `lastAttemptAt`
- `claimedAt`
- `claimOwner`
- `claimToken`
- `claimGeneration`
- `claimExpiresAt`
- `publishedAt`
- `lastErrorCode`
- `lastErrorMessage`

Allowed statuses are only:

- `PENDING`
- `PUBLISHING`
- `PUBLISHED`
- `FAILED_RETRYABLE`
- `FAILED_PERMANENT`

## Success Semantics

A write-action success response means the business record and write-action audit outbox record are durable. It does not
mean the final audit event has already been emitted by `AuditService`.

For FDP-122 fraud feedback, `FraudFeedbackService` writes the feedback record and outbox intent through the existing
`RegulatedMutationTransactionRunner`. In transaction mode `REQUIRED`, both writes participate in the configured local
Mongo transaction. In default local mode `OFF`, outbox persistence failure returns `503` and the service performs the
same bounded local cleanup style used by existing feedback writes. This fallback is not claimed as distributed or full
atomicity.

`503 FRAUD_FEEDBACK_AUDIT_OUTBOX_UNAVAILABLE` means feedback could not be safely completed because durable write-action
audit outbox intent could not be persisted.

## Metadata Safety

Outbox metadata is bounded and must not contain raw or sensitive payloads.

Allowed examples include transaction id, feedback id, feedback label, feedback status, correlation id, actor, endpoint
shape, service name, and contract version.

Forbidden metadata includes raw notes, raw customer payloads, raw transaction payloads, raw ML requests, raw ML
responses, raw feature vectors, raw evidence, tokens, secrets, passwords, stack traces, full exception messages, payment
authorization data, payment decisions, final decisions, ground truth, and training labels.

Unsafe metadata is rejected before persistence with `WRITE_ACTION_AUDIT_OUTBOX_METADATA_UNSAFE`.

## Publisher

`WriteActionAuditOutboxPublisher` is invoked by an internal scheduled component,
`WriteActionAuditOutboxScheduler`. The scheduler is enabled by default with:

- `app.audit.outbox.publisher.enabled=true`
- `app.audit.outbox.publisher.fixed-delay-ms=30000`
- `app.audit.outbox.publisher.claim-lease-ms=300000`

It can be disabled by setting `app.audit.outbox.publisher.enabled=false`.

The Security / Operations workspace exposes a narrowly authorized recovery path for governance audit obligations in
`FAILED_PERMANENT`. The `model-policy:audit:recover` authority is separate from ordinary audit read, publication
recovery, analyst roles, and unscoped fraud operations. Recovery also requires audit-read and production scope so the
operator can inspect the evidence being acted on.

`PUBLISHING` is a leased claim state, not a terminal state. A successful claim stores `claimedAt`, `claimOwner`, a
unique `claimToken`, incremented `claimGeneration`, and `claimExpiresAt`; the default lease is five minutes. Terminal
updates use Mongo CAS over the outbox id, `PUBLISHING` status, exact owner, token, generation, claim time, unexpired
lease, and expected retry counters. A stale claim can neither complete nor clear a newer worker's lease. No terminal
transition falls back to `save`.

If a publisher process crashes after the claim and before
`AuditService` publication or before marking the record published/failed, a later scheduler run can recover stale
`PUBLISHING` records after `claimExpiresAt <= now`. This lease recovery is internal to the scheduled publisher;
operator recovery applies only after an obligation reaches `FAILED_PERMANENT`.

The publisher:

- finds `PENDING`, eligible `FAILED_RETRYABLE`, and stale `PUBLISHING` records whose claim lease expired
- atomically claims each candidate with Mongo `findAndModify`
- moves successfully claimed candidates to `PUBLISHING` before calling `AuditService`
- skips records when claim fails, so concurrent publishers do not both call `AuditService` for the same record
- leaves fresh `PUBLISHING` records owned by another publisher unclaimed until their `claimExpiresAt` expires
- skips `PUBLISHED` records
- uses a bounded batch size of 50
- uses an injected `Clock` for deterministic tests
- calls `AuditService`
- marks records `PUBLISHED` only when its current claim CAS succeeds and clears claim fields atomically
- increments `attemptCount` on failure
- sets `lastAttemptAt`, `nextAttemptAt`, and `lastErrorCode`
- keeps transient failures `FAILED_RETRYABLE` while attempts remain and clears claim fields atomically
- marks records `FAILED_PERMANENT` after `maxAttempts`; cryptographic hash failures and immutable receipt conflicts are permanent immediately
- never retries forever
- stores only safe bounded error codes and messages

Governance audit appends keep the deterministic request id derived from their immutable event commitment. After a
crash or lost acknowledgement, a later claim verifies and reconciles that exact append before attempting publication.
Operator recovery classifies the commitment and deterministic request ID before changing state. A verified existing
append only changes the outbox acknowledgement to `PUBLISHED`; a verified absent request can queue at most three
bounded retries. Attempt counters and original commitment fields are retained, and each retry extends the total attempt
threshold by one. Recovery uses CAS from `FAILED_PERMANENT`, so it cannot take over an active claim. A Mongo transaction
updates the obligation and stores an immutable, hashed recovery receipt under its operation ID. The receipt is the
terminal evidence for the recovery action and deliberately creates no second audit obligation.

Conflicting operation ID reuse, an active/stale claim, exhausted recovery budget, unavailable evidence, or invalid
commitment evidence fails closed. An unavailable Mongo read is not treated as proof that no append exists. Non-governance
audit outbox behavior retains its existing at-least-once semantics.

## Exact-obligation recovery evidence

Recovery verifies one immutable obligation in a Mongo read transaction using the unique audit `request_id`, its exact local
anchor position, adjacent chain positions, current head and anchor, plus outbox claim and audit-chain lock state. It does not
scan the audit partition. `NO_APPEND_PROVEN` requires the original business event and outbox commitment to match, no event for
the unique request id, a terminal retryable/permanent outbox state with all claim fields cleared, an unowned and released
audit-chain lease, and a canonical head with its matching anchor and immediate predecessor. A live or retained lock owner,
an unreleased lock expiry, publishing claim,
read failure, or unresolved snapshot yields `OUTCOME_UNKNOWN`/`INFRASTRUCTURE_UNAVAILABLE`; it is never treated as absence.

`EXACT_AUDIT_APPEND_COMMITTED` requires the event payload and hash to match the commitment, a valid adjacent predecessor,
the exact local anchor, and a consistent current head. Missing or mismatched local anchors and invalid hashes remain integrity
failures. Independent witness availability is reported separately from the local append result. This exact-obligation result
does not claim that the entire historical chain was verified; full-chain and bounded-range verification remain separate,
explicit operations.

Authorized recovery performs an initial eligibility check, then repeats cryptographic evidence verification inside the same
Mongo transaction as the outbox compare-and-set and immutable recovery receipt. The transaction snapshot binds ordinary
application reads of the governance source, commitment, audit event, local anchor, chain head, and lock to that decision;
the compare-and-set also rejects changed commitment fields, attempts, recovery count, status, or publisher claims. A snapshot
does not protect against a privileged database administrator rewriting all related evidence or trust roots, and an external
witness cannot participate in Mongo atomicity. Those cases remain outside this guarantee.
