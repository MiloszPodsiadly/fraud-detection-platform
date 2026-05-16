# FDP-37 Enablement Decision Checklist

Status: branch evidence.


This checklist records whether FDP-37 evidence is ready for enablement review. It does not enable production mode.

Allowed decisions:

- `NOT_READY`
- `READY_WITH_LIMITATIONS`
- `READY_FOR_ENABLEMENT_REVIEW`

There is no production-enabled decision value. `READY_FOR_ENABLEMENT_REVIEW` is not production enablement.

READY_FOR_ENABLEMENT_REVIEW is not production enablement.

## Required Inputs

| Item | Evidence |
| --- | --- |
| FDP-37 production-image chaos | `fdp37-production-image-chaos` green on `ubuntu-latest` |
| Current commit image | proof summary contains `fdp37-alert-service:${GITHUB_SHA}`, commit SHA, and digest/id |
| Immutable image provenance | proof summary and enablement pack contain Docker image id, digest or image id, and `deployment/Dockerfile.backend` |
| Generated enablement review pack | `target/fdp37-chaos/fdp37-enablement-review-pack.md` and `.json` uploaded and placeholder-free |
| Required test execution | Surefire XML for required FDP-37 classes exists with zero skips |
| REQUIRED transaction-mode row | evidence summary contains `transaction_mode=REQUIRED` |
| Shared network proof | evidence summary contains `network_mode=testcontainers-shared-network` and `host_networking_used=false` |
| Rollback validation | `target/fdp37-chaos/fdp37-rollback-validation.md` uploaded |
| Regression gate | `regulated-mutation-regression` green |
| Prior chaos/readiness gates | `fdp36-real-chaos` and `fdp35-production-readiness` green |
| Dashboard readiness | `docs/ops/fdp_37_dashboard_and_alert_thresholds.md` reviewed |
| Config review | separate production config review remains required |
| Release/config PR | separate approval required before any production or bank enablement |
| Final decision | one of `NOT_READY`, `READY_WITH_LIMITATIONS`, `READY_FOR_ENABLEMENT_REVIEW` |

## Non-Claim Confirmation

The reviewer must confirm:

- FDP-37 is production-image durable crash-window proof, not production config certification.
- FDP-37 uses explicit CI/test configuration against Testcontainers dependencies.
- FDP-37 uses a shared Testcontainers network with stable aliases; it does not use host networking.
- FDP-37 does not certify production networking, secrets, Kafka delivery, external finality, distributed ACID, or bank operation.
- Live in-flight production-image chaos is optional/future scope unless a separate fixture job makes it non-skippable. A skipped live in-flight test is not counted as proof.

## Sample Filled Output

| Item | Result |
| --- | --- |
| FDP-37 production-image chaos | `PASS` |
| Required test skips | `0` |
| Current commit image evidence | `PASS` |
| REQUIRED transaction-mode proof | `PASS` |
| Rollback validation artifact | `PASS` |
| Config review complete | `PENDING_SEPARATE_RELEASE_REVIEW` |
| Final decision | `READY_FOR_ENABLEMENT_REVIEW` |

FDP-37 does not enable FDP-29. Any production or bank enablement requires a separate release/config PR and human approval.
