# FDP-35 Merge Gate

FDP-35 may merge only as a production-readiness proof branch. It must not introduce new regulated mutation semantics.

## Required Checks

- Docker/Testcontainers E2E proof suite green.
- Controller/API recovery response tests green.
- Modeled restart/recovery proof green.
- FDP-29, FDP-32, FDP-33, and FDP-34 regression tests remain green.
- Recovery runbook has filled sample output.
- Dashboard specifications exist.
- Alert threshold specifications exist.
- Rollback plan exists and is validated by tests or explicit documented checks.
- No production enablement config changed.

## Hard No Claims

- No real process kill proof unless a test actually kills and restarts the process.
- No external finality.
- No distributed lock.
- No distributed ACID.
- No WORM/legal notarization/KMS claim.
- No automatic FDP-29 production enablement.

## No New Semantics Gate

- `SubmitDecisionOperationStatus` values unchanged.
- `RegulatedMutationState` values unchanged.
- `RegulatedMutationModelVersion` values unchanged.
- No public heartbeat endpoint.
- Controllers do not call lease renewal or checkpoint renewal services.
- Kafka/outbox publisher contracts unchanged.
- Readiness tests do not call external anchor or Trust Authority clients.

## Approval Decision

Approve only if all required checks are green and the branch remains proof/readiness only.

