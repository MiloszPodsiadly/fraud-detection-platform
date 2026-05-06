# FDP-37 Final Proof Pack

## Scope

FDP-37 proves production-image chaos readiness for regulated mutation restart safety. It builds or reuses the production-like `alert-service` Docker image, kills the real image/container, restarts it against durable Mongo state, and verifies API/recovery/outbox/audit invariants.

## Non-Goals

- not production enablement
- not bank certification
- not external finality
- not distributed ACID
- not Kafka exactly-once
- not full network partition chaos
- not automatic FDP-29 production mode

## Architecture Flow

FDP-31 introduced regulated mutation command/replay foundations. FDP-32 added lease-owner fenced transitions. FDP-33/FDP-34 added bounded renewal and checkpoint adoption. FDP-35 added production-readiness recovery proof. FDP-36 killed and restarted the real alert-service JVM/process. FDP-37 upgrades the proof target to the production-like `alert-service` Docker image/container.

## Proof Levels

- `REAL_ALERT_SERVICE_JVM_KILL`
- `LIVE_IN_FLIGHT_REQUEST_KILL`
- `PRODUCTION_IMAGE_CONTAINER_KILL`
- `PRODUCTION_IMAGE_RESTART_API_PROOF`
- `DURABLE_STATE_SEEDED_CONTAINER_PROOF`
- `API_PERSISTED_STATE_PROOF`

## Invariant To Test Matrix

| Invariant | Test evidence |
| --- | --- |
| no false success after production-image kill | `RegulatedMutationProductionImageChaosIT.productionImageKillDuringLegacyBusinessCommittingRequiresRecoveryWithoutFalseSuccess` |
| no duplicate outbox after restart | `RegulatedMutationProductionImageEvidenceIntegrityIT.legacyReplayAfterProductionImageRestartDoesNotCreateSecondOutboxRecord` |
| no duplicate SUCCESS audit after restart | `RegulatedMutationProductionImageEvidenceIntegrityIT.legacyReplayAfterProductionImageRestartDoesNotCreateSecondSuccessAudit` |
| pending external remains pending | `RegulatedMutationProductionImageChaosIT.productionImageKillInFdp29PendingExternalRemainsPendingWithoutEvidence` |
| rollback keeps recovery explicit | `RegulatedMutationProductionImageRollbackIT.rollbackRestartKeepsFdp32FencingAndDoesNotCreateNewSuccessClaims` |
| production-image defaults are explicit | `RegulatedMutationProductionImageConfigParityIT.productionImageStartsWithSafeRegulatedMutationDefaultsAndNoChaosProfile` |

## CI Job Matrix

| Job | Required result | Artifact |
| --- | --- | --- |
| `fdp37-production-image-chaos` | green | `fdp37-production-image-chaos-reports` |
| `regulated-mutation-regression` | green | regulated mutation regression reports |
| `fdp36-real-chaos` | green | FDP-36 proof summary |
| `fdp35-production-readiness` | green | FDP-35 reports |

## Production Image Evidence

The required artifact paths are:

- `alert-service/target/fdp37-chaos/fdp37-proof-summary.md`
- `alert-service/target/fdp37-chaos/fdp37-proof-summary.json`
- `alert-service/target/fdp37-chaos/evidence-summary.md`

The artifact must include image name, killed container id, restarted container id, proof levels, scenarios, invariants, and final result. Raw idempotency keys, request hashes, lease owners, actor ids, tokens, and stack traces are not allowed.

## Rollback Validation

Rollback validation proves that disabling optional enablement-oriented flags does not disable FDP-32 fencing and does not create new success claims, duplicate outbox records, or duplicate SUCCESS audit records.

## Enablement Checklist Status

Use `docs/FDP-37-enablement-decision-checklist.md`. `READY_FOR_ENABLEMENT_REVIEW` is not production enablement.

## Known Limitations

FDP-37 does not prove external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, or full network partition chaos. Any such claim requires separate evidence.

## Final Decision

GO for merge if required CI is green and proof artifacts exist. NOT production enablement.
