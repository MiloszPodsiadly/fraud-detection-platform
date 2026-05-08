# Configuration Guide

Status: current configuration guide.

## Scope

This guide summarizes configuration posture for local, test, production-like, and fixture profiles. It does not
enable production mode and does not replace environment-specific release approval.

## Environment Truth Table

| Environment | Allowed transaction mode | FDP-29 enablement | Fixture profiles | Checkpoint barriers | Production enablement | Required guardrails |
| --- | --- | --- | --- | --- | --- | --- |
| local/dev | `OFF` or `REQUIRED` for local verification. | Local only when explicitly configured. | Allowed for local tests only. | Allowed only in test harnesses. | No. | Demo/local limitations must be visible. |
| test | `OFF` or `REQUIRED` depending on scenario. | Allowed for automated coverage. | Allowed. | Allowed. | No. | Fixtures must be labeled as test evidence. |
| production-like | `REQUIRED` for regulated mutation safety. | Only as readiness validation before separate approval. | Not allowed. | Not allowed. | No. | Startup guards and release controls must fail closed. |
| FDP-38 fixture image/profile | Scenario-specific test mode only. | Test-fixture proof only. | Required by fixture design. | Allowed. | No. | Must never be promoted as release image or release profile. |
| release image | `REQUIRED` for bank/prod-style regulated mutation safety. | Only after separate config/release PR. | Not allowed. | Not allowed. | Separate approval required. | Immutable digest, required checks, runbooks, and rollback plan. |

## Regulated Mutation Settings

- `app.regulated-mutations.transaction-mode=OFF` is compatibility/demo behavior for regulated paths.
- `REQUIRED` is expected for bank/prod-style regulated mutation safety.
- Lease duration, renewal, and checkpoint budgets must be reviewed with stale-worker metrics before enablement.
- FDP-29 evidence-gated finalize requires a separate config PR and explicit release approval.
- Checkpoint renewal is ownership preservation only. It is not proof of business progress.
- FDP-38 checkpoint barriers are fixture/test-only controls and must never be included in a release image claim.

## Messaging And Outbox

- Kafka/outbox delivery is asynchronous and at-least-once.
- Consumers must deduplicate by event id where required.
- Manual outbox confirmation and recovery visibility are operational evidence workflows, not business approval.

## Release Governance Settings

- Mutable tag deployment is NO-GO for release proof.
- Missing image digest is NO-GO for release proof.
- Fixture profiles and fixture images must not be used as release profiles or release images.
- Signing readiness does not mean signing enforcement unless the artifact explicitly proves enforcement.

## Required Production-Like Review

Before any production-like enablement, reviewers must verify:

- JWT/OIDC or internal service identity is configured for the intended boundary.
- Demo/local bypasses are disabled.
- Sensitive reads are audited and fail closed where required.
- Rate limiting exists for ops/recovery/inspection endpoints.
- Rollback and recovery runbooks are current.
- Required branch protection checks are green.
