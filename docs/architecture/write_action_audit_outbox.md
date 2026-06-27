# Write-Action Audit Outbox

Status: FDP-122 infrastructure foundation.

## Scope

FDP-122 adds an alert-service-owned write-action audit outbox for bounded business writes that must not return success
unless the business record and durable audit intent are both persisted.

This is not a public API, not a public recovery API, not an admin UI, not a Kafka outbox, not a dataset export, not ML
evaluation, and not a training-label pipeline. It is not a replacement for the existing decision event outbox in
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

No public recovery endpoint, admin UI, public outbox API, or Kafka publication is added in FDP-122.

`PUBLISHING` is a leased claim state, not a terminal state. A successful claim stores `claimedAt`, `claimOwner`, and
`claimExpiresAt`; the default lease is five minutes. If a publisher process crashes after the claim and before
`AuditService` publication or before marking the record published/failed, a later scheduler run can recover stale
`PUBLISHING` records after `claimExpiresAt <= now`. The recovery is internal to the scheduled publisher. FDP-122 adds
no public recovery endpoint and no manual recovery API for this outbox.

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
- marks records `PUBLISHED` on success and clears `claimedAt`, `claimOwner`, and `claimExpiresAt`
- increments `attemptCount` on failure
- sets `lastAttemptAt`, `nextAttemptAt`, and `lastErrorCode`
- keeps failures `FAILED_RETRYABLE` while attempts remain and clears `claimedAt`, `claimOwner`, and `claimExpiresAt`
- marks records `FAILED_PERMANENT` after `maxAttempts` and clears `claimedAt`, `claimOwner`, and `claimExpiresAt`
- never retries forever
- stores only safe bounded error codes and messages

Publication is at-least-once. A crash after `AuditService.audit` but before marking the outbox record `PUBLISHED` can
cause re-publication after lease expiry. Exact-once audit effects require downstream `AuditService` idempotency or
equivalent consumer-side deduplication. Metrics and stale-pending monitoring are not added in FDP-122; they remain
future operational hardening scope. Stale `PUBLISHING` recovery is implemented in FDP-122 and is not future scope.
