# FDP-28 Invariant Proof Report

FDP-28 is an invariant and failure-injection test pack for alert-service. It adds tests and documentation around existing regulated mutation, outbox, audit, and trust-level behavior.

## Evidence Added

| Area | Evidence |
| --- | --- |
| Regulated mutation crash windows | `RegulatedMutationCrashWindowInvariantTest` covers ATTEMPTED audit failure before business write, SUCCESS audit failure after business commit, and committed-without-snapshot recovery-required behavior. |
| Fraud-case non-terminal response | `FraudCaseMutationInvariantTest` proves non-terminal updates return status/current snapshot, not target committed business fields. |
| Transactional outbox ambiguity | `TransactionalOutboxFailureInjectionTest` proves stale publish attempts become confirmation-unknown, not published, and projection repair uses authoritative outbox state. |
| Sensitive read audit failure | `SensitiveReadAuditFailureInjectionTest` proves fail-open local behavior remains observable and fail-closed bank behavior returns explicit 503. |
| Bank/profile misconfiguration | `BankProfileMisconfigurationMatrixTest` covers unsafe prod/bank startup combinations. |
| No false healthy | `NoFalseHealthyInvariantTest` proves unavailable outbox/trust incident control-plane state cannot produce `FDP24_HEALTHY`. |
| Test support | `FailureInjectionPoint`, `FailureScenarioRunner`, `InvariantAssert`, and `CrashWindowTestSupport` provide bounded test-only failure helpers. |

## Assertions

- ATTEMPTED audit failure prevents business mutation.
- SUCCESS audit failure after business mutation is durable `COMMITTED_DEGRADED`.
- `COMMITTED_DEGRADED` is not presented as fully anchored or confirmed.
- Recovery-required states do not trigger business mutation replay.
- Outbox publish confirmation ambiguity is explicit.
- Projection repair does not infer success from the alert projection.
- Sensitive read audit failure is measured and fail-closed where required.
- Bank/prod unsafe configuration fails startup.

## Non-Claims

FDP-28 does not claim:

- distributed ACID across Mongo, audit, outbox, and external anchors
- exactly-once broker delivery
- Evidence-Gated Finalize
- legal/regulatory finality
- rollback of already committed business state after SUCCESS audit failure

The branch strengthens proof coverage for the current model. It does not change scoring, ML model behavior, Kafka contracts, or governance advisory semantics.
