# Write-Action Audit Outbox

Status: FDP-122 infrastructure foundation.

## Scope

FDP-122 adds an alert-service-owned write-action audit outbox for bounded business writes that must not return success
unless the business record and durable audit intent are both persisted.

This is not a public API, not a Kafka outbox, and not a replacement for the existing decision event outbox in
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
- `publishedAt`
- `lastErrorCode`
- `lastErrorMessage`

Allowed statuses are only:

- `PENDING`
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

## Metadata Safety

Outbox metadata is bounded and must not contain raw or sensitive payloads.

Allowed examples include transaction id, feedback id, feedback label, feedback status, correlation id, actor, endpoint
shape, service name, and contract version.

Forbidden metadata includes raw notes, raw customer payloads, raw transaction payloads, raw ML requests, raw ML
responses, raw feature vectors, raw evidence, tokens, secrets, passwords, stack traces, full exception messages, payment
authorization data, payment decisions, final decisions, ground truth, and training labels.

Unsafe metadata is rejected before persistence with `WRITE_ACTION_AUDIT_OUTBOX_METADATA_UNSAFE`.

## Publisher

`WriteActionAuditOutboxPublisher` is invokable only in FDP-122. No scheduler framework or public recovery endpoint is
added.

The publisher:

- finds `PENDING` and eligible `FAILED_RETRYABLE` records
- skips `PUBLISHED` records
- uses a bounded batch size of 50
- uses an injected `Clock` for deterministic tests
- calls `AuditService`
- marks records `PUBLISHED` on success
- increments `attemptCount` on failure
- sets `lastAttemptAt`, `nextAttemptAt`, and `lastErrorCode`
- keeps failures `FAILED_RETRYABLE` while attempts remain
- marks records `FAILED_PERMANENT` after `maxAttempts`
- never retries forever
- stores only safe bounded error codes and messages

Metrics are not added in FDP-122. They remain future operational hardening scope.
