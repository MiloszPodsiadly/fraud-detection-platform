# Regulated Mutation Drill Runbook

Status: current drill runbook.

## Purpose

This runbook records modeled recovery drills and real alert-service kill-restart drills for regulated mutation recovery.
It separates proof scope from production enablement.

## Scope

FDP-35 provides modeled restart/recovery proof in CI. It verifies durable post-crash command states, replay policy,
recovery API behavior, and operator visibility. It does not claim real OS/JVM/container process-kill chaos unless an
explicit real-chaos job is implemented and run.

True OS/JVM/container termination chaos remains future scope unless explicitly implemented.

FDP-36 provides real alert-service JVM/process kill-restart proof over selected durable crash-window states. It does
not change regulated mutation semantics.

FDP-36 kills and restarts the real alert-service JVM/process. Most crash windows are durable-state crash-window proofs,
not live in-flight instruction-boundary kills.

Docker/Testcontainers are infrastructure dependencies, not the killed alert-service image. The real-chaos proof kills
the actual alert-service JVM/process running regulated mutation execution, recovery, and inspection endpoints.

## Ownership

- Owner: Fraud Platform On-call
- Approver: Fraud Platform Incident Lead
- Required inspection authority: `regulated-mutation:recover` or `audit:verify`
- Required recovery authority: `regulated-mutation:recover`
- Dual control: required for manual state repair, rollback approval, feature flag disablement, or renewal budget changes

## Modeled Recovery Drill

Checklist:

1. Identify command by approved lookup, preferably idempotency hash.
2. Inspect state, execution status, lease expiry, lease renewal count, degradation reason, response snapshot presence,
   outbox record, and audit phases.
3. Confirm recovery state wins over stale response snapshots.
4. Confirm no duplicate business mutation occurred.
5. Record audit note with command state and action taken.
6. Escalate if durable proof is ambiguous.

Reason-specific actions are defined in [Regulated mutation recovery](regulated_mutation_recovery.md).

## Real Chaos Drill Fields

| Field | Allowed value |
| --- | --- |
| target | `alert-service` |
| proof level | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF`, `LIVE_IN_FLIGHT_REQUEST_KILL`, or `MODELED_DURABLE_STATE_PROOF` |
| scenario id | stable drill id |
| command reference | command id or idempotency hash only |
| before kill state | durable command state |
| after restart state | durable command state and recovery API response |
| forbidden actions check | boolean |
| result | pass/fail with reason |

Proof Level vs Non-Claimed Chaos Level:

| Proof level | Meaning | Non-claim |
| --- | --- | --- |
| `REAL_ALERT_SERVICE_KILL` | Real alert-service JVM/process was killed. | Not production image certification. |
| `REAL_ALERT_SERVICE_RESTART_API_PROOF` | Recovery and inspection endpoints responded after restart. | Not external finality. |
| `LIVE_IN_FLIGHT_REQUEST_KILL` | In-flight request boundary was explicitly tested. | Not assumed for all crash windows. |
| `MODELED_DURABLE_STATE_PROOF` | Durable state was modeled without killing runtime process. | Not process termination evidence. |

Documentation must not call dummy-container proof real service chaos. If the killed target is not alert-service,
classify the result as `DUMMY_CONTAINER_DURABLE_STATE_PROOF` or `MODELED_DURABLE_STATE_PROOF`, not FDP-36 GO evidence.

FDP-35 provides modeled restart/recovery proof. FDP-36 proof code is test-only. No runtime hooks are introduced.
Full alert-service image container chaos is future scope.

## Forbidden Drill Output

- no raw idempotency keys
- no raw payloads
- no customer identifiers
- no stack traces
- no tokens
- no production enabled claim
- no production certification claim
- no external finality claim
- no runtime chaos hooks in executors, coordinators, or domain services
- no FDP-29 production-mode enablement

`READY_FOR_ENABLEMENT_REVIEW` is not production enablement.

## Sample Filled Output

```text
drill_id: REGULATED-MUTATION-DRILL-2026-05-16-001
operator_identity: fraud-ops-admin
approver_identity: fraud-platform-incident-lead
target: alert-service
proof_level: REAL_ALERT_SERVICE_RESTART_API_PROOF
command_reference: idempotency_hash=96e6f95f...
reason_code: FINALIZE_RECOVERY_REQUIRED
action_taken: inspected command, outbox, audit phases, and response snapshot
evidence_checked: command_state, public_status, outbox_records, success_audit_id
forbidden_actions_confirmed_not_taken: true
post_validation: backlog stable; no false success; dashboard green
timestamp: 2026-05-16T00:00:00Z
result: PASS
```
