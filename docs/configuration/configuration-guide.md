# Configuration Guide

Status: current configuration guide.

## Scope

This guide summarizes configuration posture for local, test, production-like, and fixture profiles. It does not
enable production mode and does not replace environment-specific release approval.

## Profiles

| Profile | Use | Safe assumptions | Forbidden interpretation |
| --- | --- | --- | --- |
| local/dev/docker-local | Developer and portfolio smoke testing. | Demo auth and local infrastructure may be enabled. | Not bank-grade or production enablement. |
| test | Automated tests and Testcontainers. | Fixtures may replace external services. | Not release runtime evidence unless named as fixture proof. |
| production-like | Startup guard and release-readiness validation. | Should fail closed on missing required controls. | Not production approval by itself. |
| fixture/test-only | Chaos and proof harnesses. | May contain test blockers or fixture image markers. | Must not be promoted as release image. |

## Regulated Mutation Settings

- `app.regulated-mutations.transaction-mode=OFF` is compatibility/demo behavior for regulated paths.
- `REQUIRED` is expected for bank/prod-style regulated mutation safety.
- Lease duration, renewal, and checkpoint budgets must be reviewed with stale-worker metrics before enablement.
- FDP-29 evidence-gated finalize requires a separate config PR and explicit release approval.

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
