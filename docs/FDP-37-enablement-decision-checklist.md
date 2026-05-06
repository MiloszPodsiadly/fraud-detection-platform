# FDP-37 Enablement Decision Checklist

This checklist records whether FDP-37 evidence is ready for enablement review. It does not enable production mode.

## Decision Values

- `NOT_READY`
- `READY_WITH_LIMITATIONS`
- `READY_FOR_ENABLEMENT_REVIEW`

There is no production-enabled decision value. `READY_FOR_ENABLEMENT_REVIEW` is not production enablement.

READY_FOR_ENABLEMENT_REVIEW is not production enablement.

## Required Fields

| Field | Required evidence |
| --- | --- |
| Owner | Named release owner |
| Reviewer | Named technical reviewer |
| Approver | Named approver |
| Dual-control approval | Required where bank/release policy requires it |
| CI run id | GitHub Actions run id |
| FDP-37 production-image chaos | `fdp37-production-image-chaos` green |
| Regulated mutation regression | `regulated-mutation-regression` green |
| FDP-36 proof link | `fdp36-real-chaos` artifact link |
| FDP-35 proof link | `fdp35-production-readiness` artifact link |
| Rollback validation | `RegulatedMutationProductionImageRollbackIT` green |
| Dashboard readiness | `docs/ops/FDP-37-dashboard-and-alert-thresholds.md` reviewed |
| Alert threshold readiness | concrete thresholds reviewed |
| Operator drill status | dry run complete or explicitly scheduled |
| Production flags | currently disabled unless separate release/config PR enables them |
| Final decision | one of `NOT_READY`, `READY_WITH_LIMITATIONS`, `READY_FOR_ENABLEMENT_REVIEW` |

## Sample Filled Output

| Field | Value |
| --- | --- |
| Owner | release-owner |
| Reviewer | regulated-mutation-reviewer |
| Approver | bank-change-approver |
| CI run id | `TO_BE_FILLED_BY_CI` |
| FDP-37 production-image chaos | `PASS` |
| Regulated mutation regression | `PASS` |
| Rollback validation | `PASS` |
| Dashboard readiness | `PASS` |
| Alert threshold readiness | `PASS` |
| Operator drill status | `SCHEDULED` |
| Production flags | `DISABLED` |
| Final decision | `READY_FOR_ENABLEMENT_REVIEW` |

FDP-37 does not enable FDP-29. Any production or bank enablement requires a separate release/config PR.
