# Alert Service Failure Windows

FDP-28 documents and tests the current regulated mutation crash windows in alert-service with modeled failure injection, invariant proof tests, and targeted transaction integration tests. It does not introduce a new business workflow and it does not implement Evidence-Gated Finalize.

## Current Model

Regulated mutations are command-backed and idempotency-keyed. The command record captures request intent, execution state, audit phase state, response snapshots, local commit markers, and degradation reason codes.

The current model provides:

- durable command state for retry and inspection
- ATTEMPTED audit before business mutation
- explicit rejection when ATTEMPTED audit cannot be written
- SUCCESS audit after local business mutation
- durable post-commit degradation detection when SUCCESS audit fails
- recovery and reconciliation entry points for ambiguous states
- trust-level downgrade when degraded evidence or recovery backlog exists

It does not guarantee that a business mutation can never commit before SUCCESS evidence exists. A committed mutation plus failed SUCCESS audit remains a known post-commit degradation window.

## Crash Windows

| Window | Expected State | Expected Public Meaning |
| --- | --- | --- |
| Command created, not claimed | `REQUESTED` / `NEW` | Safe retry; no committed result is implied |
| Claimed, before ATTEMPTED audit | `PROCESSING` lease | Duplicate requests return in-progress while lease is active |
| ATTEMPTED audit failed | `REJECTED` / `FAILED` | Business mutation must not run |
| Business committing without snapshot | `BUSINESS_COMMITTING` | Commit outcome unknown; recovery required |
| Business committed without snapshot | `BUSINESS_COMMITTED` | Recovery required; no terminal success claim |
| Local commit marker with response snapshot | `SUCCESS_AUDIT_PENDING` | SUCCESS audit retry only; business mutation is not rerun |
| SUCCESS audit failed after commit | `COMMITTED_DEGRADED` | Explicit evidence incomplete state, trust downgrade |
| Outbox publish attempted and stale | `PUBLISH_CONFIRMATION_UNKNOWN` | Not equivalent to published |
| Projection mismatch | `projection_mismatch=true` | Repaired from authoritative outbox record |
| Sensitive read audit unavailable in fail-closed mode | 503 | Read does not proceed as successful |

## Invariants

- No `REJECTED` mutation may imply a committed business state.
- No `BUSINESS_COMMITTING` or `BUSINESS_COMMITTED` command without a snapshot may be projected as final success.
- `COMMITTED_DEGRADED` must remain explicit and durable.
- `PUBLISH_CONFIRMATION_UNKNOWN` must not be treated as published.
- Trust level must not report `FDP24_HEALTHY` when recovery backlog, outbox ambiguity, trust incident control-plane failure, or post-commit audit degradation exists.
- Sensitive read audit failure is observable; in bank/prod fail-closed mode it blocks the read.

## Explicit Limitation

FDP-28 proves and guards the existing failure behavior. It does not eliminate the post-commit SUCCESS audit window transactionally. That is FDP-29 scope.

FDP-28 does not yet simulate real JVM death or OS-level process crash. Integration crash tests may be added in FDP-28B.
