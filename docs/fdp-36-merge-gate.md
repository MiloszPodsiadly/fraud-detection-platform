# FDP-36 Merge Gate

FDP-36 provides real alert-service JVM/process kill-restart proof over selected durable crash-window states. It does not change regulated mutation semantics.

FDP-36 kills and restarts the real alert-service JVM/process. Most crash windows are durable-state crash-window proofs, not live in-flight instruction-boundary kills.

Docker/Testcontainers are infrastructure dependencies, not the killed alert-service image.

## Required CI

- `fdp36-real-chaos` must be green and must report killed target `alert-service`.
- `regulated-mutation-regression` must be green.
- FDP-36 GO requires both `fdp36-real-chaos` and `regulated-mutation-regression` green.
- `fdp35-production-readiness` remains green.
- Docker/Testcontainers must be available; no Docker skip is acceptable for the required FDP-36 real-chaos job.
- Surefire/failsafe artifacts must be uploaded.
- `fdp36-proof-summary.md` must be uploaded as part of `fdp36-real-chaos-test-reports`.

## Required Proof

- selected real alert-service JVM/process kill-restart windows are covered against actual `alert-service`
- proof matrix rows include a `Proof Level` column
- proof matrix rows include `State Reach Method`, `Runtime In-Flight Kill?`, and `Exact Claim`
- `REAL_ALERT_SERVICE_KILL` rows identify `alert-service` as the killed target
- `REAL_ALERT_SERVICE_RESTART_API_PROOF` rows verify through the restarted HTTP API
- any `LIVE_IN_FLIGHT_REQUEST_KILL` row must be marked runtime-reached and scoped to one checkpoint
- Mongo durable command state survives restart
- recovery/inspection API remains explicit after restart
- stale response snapshots do not win over recovery states
- local committed pending external stays pending, not confirmed
- no false committed success
- no false finalized confirmed
- no duplicate mutation
- no duplicate outbox
- no duplicate SUCCESS audit
- no fake external confirmation
- inspection endpoint governance must be proven or explicitly marked production-gate.

## Required Non-Changes

- no business behavior change
- no public status change
- no transaction-boundary change
- no Kafka/outbox semantics change
- no production runtime chaos hook
- no FDP-29 production default change

FDP-36 proof code is test-only. No runtime hooks are introduced.

## Merge Inputs

- `docs/testing/fdp-36-real-chaos-proof-matrix.md`
- `docs/fdp-36-enablement-decision-checklist.md`
- `docs/runbooks/fdp-36-real-chaos-recovery-drill-runbook.md`
- `RegulatedMutationRealAlertServiceChaosIT`
- `RegulatedMutationRealAlertServiceEvidenceIntegrityIT`
- `RegulatedMutationPostRestartApiBehaviorTest`
- `RegulatedMutationArchitectureTest`

## Non-Claims

This merge gate is not production enablement. It is not production certification. It does not provide external finality, distributed ACID, distributed lock, exactly-once Kafka, legal notarization, WORM storage, KMS/HSM proof, or automatic bank enablement.
