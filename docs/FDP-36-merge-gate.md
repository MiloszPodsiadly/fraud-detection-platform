# FDP-36 Merge Gate

FDP-36 provides real Docker/container kill-restart proof for selected regulated mutation crash windows. It does not change regulated mutation semantics.

## Required CI

- `fdp36-real-chaos` must be green.
- `regulated-mutation-regression` must be green.
- `fdp35-production-readiness` remains green.
- Docker/Testcontainers must be available; no Docker skip is acceptable for the required FDP-36 real-chaos job.
- Surefire/failsafe artifacts must be uploaded.

## Required Proof

- selected real Docker/container kill-restart windows are covered
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

## Required Non-Changes

- no business behavior change
- no public status change
- no transaction-boundary change
- no Kafka/outbox semantics change
- no production runtime chaos hook
- no FDP-29 production default change

## Merge Inputs

- `docs/testing/FDP-36-real-chaos-proof-matrix.md`
- `docs/FDP-36-enablement-decision-checklist.md`
- `docs/runbooks/FDP-36-real-chaos-recovery-drill-runbook.md`
- `RegulatedMutationRealChaosIT`
- `RegulatedMutationRealChaosEvidenceIntegrityIT`
- `RegulatedMutationPostRestartApiBehaviorTest`
- `RegulatedMutationArchitectureTest`

## Non-Claims

This merge gate is not production enablement. It is not production certification. It does not provide external finality, distributed ACID, distributed lock, exactly-once Kafka, legal notarization, WORM storage, KMS/HSM proof, or automatic bank enablement.
