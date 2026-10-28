# Device Risk Staged Candidate Lifecycle and Recovery

Status: accepted for the disabled persistence foundation; production recovery and admission remain blocked.

## Context: current state machine

`MongoDeviceObservationStore` is package-private, has no production bean, and is reachable only from tests today.
`fraud.device-risk.admission.enabled` defaults to `false`; enabling it fails startup because no authenticated issuer,
scoped authorization, or signed coverage seal exists. The `AUTHENTICATED_INSTITUTION` value accepted by the store is a
structural claim, not proof of source authenticity.

The current candidate transitions are:

| Current state | Operation | Durable result | Pending counter |
| --- | --- | --- | --- |
| absent | `stage` | insert `STAGED` candidate with `stagedAt` and increment source `pendingCount` in one transaction | `+1` |
| `STAGED` | duplicate `stage` with identical identity and commitment | return `DUPLICATE` | unchanged |
| `STAGED` | `commit` before a sufficient seal or relevant predecessor | return a wait status; candidate stays `STAGED` | unchanged |
| `STAGED` | successful `commitInside` | insert observation and immutable occurrence, set candidate `COMMITTED`, decrement counter in one transaction | `-1` |
| `STAGED` | capacity quarantine | set candidate `QUARANTINED`, invalidate source coverage, decrement counter in one transaction | `-1` |
| `STAGED` | operational device revocation | write revocation tombstone, quarantine matching candidates, remove active membership, invalidate source coverage and reconcile the counter in one transaction | reconciled |

Unique indexes protect source-event, transaction, and sequence identity. The source seal checks contiguous staged
sequence and event-time bounds, then advances the source coverage record transactionally. It is not cryptographically
verified. Transaction retries are bounded to three attempts; unknown commit acknowledgements are retried up to three
times and callers reconcile durable occurrence/candidate state before reporting an outcome.

`STAGED` candidates intentionally have no TTL. New candidates record `stagedAt`; the `(status, stagedAt)` index
supports bounded aggregate inspection. Startup rejects a staged row without a BSON date there and does not backfill
it. `pendingMetrics()` returns only an aggregate count and oldest pending age; it is not wired to a monitoring
backend. Terminal candidates expire 45 days after disposition. A source can remain
at the 10,000-candidate cap indefinitely when a seal never arrives. The current adapter has no worker, lease, operator
repair/closure path, or audit integration. `LATE_AND_REPAIR` bounds whether history is complete for an event; it is
not a source-liveness timeout and must not be reused to expire staged candidates.

## Decision

Do not delete or quarantine a staged candidate solely because processing time elapsed. Do not invent a source epoch,
source-death timeout, or retention period. A missing seal means history is unavailable; it never means a complete empty
window. Keep the admission and any recovery worker disabled until source authenticity, source-liveness semantics,
retention, operator authorization, audit, and rollback ownership are independently approved.

A future controlled recovery path must use a source-liveness assertion bound to institution, source, and epoch; a
bounded lease/fence for worker ownership; and a single transactional compare-and-set from `STAGED` to one terminal
state with exactly one pending-counter decrement and source-coverage invalidation. Operator closure must require
strong authorization and a durable audit record. Reopening work must be explicit, reversible, and unable to rewrite a
committed occurrence. Metrics may expose aggregate count and oldest pending age, with no institution, source,
customer, device, event, or transaction labels; the adapter's read-only query follows this boundary.

## Evidence boundary and tests

Mongo Testcontainers tests use deterministic synthetic identifiers and structural source claims. They are labeled
`UNTRUSTED_TEST_ONLY`; they prove transaction and state behavior for supplied values, not issuer authenticity,
production source liveness, retention approval, or operational recovery. Tests must continue to prove no TTL on
`STAGED`, bounded pending capacity, duplicate/idempotent seal behavior, gaps, event-time ordering, restart replay,
concurrent commits, unknown commit handling, revocation, and exact replay. No worker/lease-expiry behavior is claimed
until an approved liveness and authorization contract exists.

## Activation blockers

- Independently authenticated issuer and scoped source authorization.
- Signed, replay-resistant sequence/coverage seals and an approved source-liveness/abandonment rule.
- Approved data retention, replay horizon, terminal receipt lifetime, and legal erasure disposition.
- Strongly authorized and audited repair/terminal closure, rollback ownership, and read-only aggregate monitoring.
- A separately gated worker with transaction, lease-expiry, restart, race, and failure-injection proof.

Passing disabled-foundation tests is not approval to admit data or activate a recovery worker.
