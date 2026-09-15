# Configuration Guide

Status: current configuration guide.

## Scope

This guide summarizes configuration posture for local, test, production-like, and fixture profiles. It does not
enable production mode and does not replace environment-specific release approval.

## Environment Truth Table

| Mode/profile | Allowed in local/dev | Allowed in test | Allowed in production-like proof | Allowed in release image | Transaction mode expectation | FDP-29 enablement rule | Fixture/checkpoint barrier rule | Required guardrails | Forbidden claims |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| local/dev | Yes. | No, use test profile instead. | No. | No. | `OFF` is compatibility/demo only; `REQUIRED` may be used for local verification. | Disabled by default; local enablement requires explicit local config. | Fixture barriers allowed only through test harnesses. | Local limitations must be visible. | Must not claim bank/prod-style safety. |
| test | No. | Yes. | No. | No. | `OFF` or `REQUIRED` depending on scenario. | Allowed for automated coverage. | Fixture barriers allowed when test-labeled. | Fixtures must be labeled as test evidence. | Must not claim production enablement. |
| production-like | No. | No. | Yes. | No. | `REQUIRED` is expected for regulated mutation safety. | Readiness validation only before separate approval. | Fixture profiles and checkpoint barriers are not allowed. | Startup guards and release controls must fail closed. | Must not claim bank certification. |
| FDP-38 fixture image/profile | No. | Yes. | Test-fixture proof only. | No. | Scenario-specific test mode only. | Test-fixture proof only. | Fixture barriers are allowed and must be labeled test-only. | Must never be promoted as release image or release profile. | Must not claim production-image proof. |
| FDP-37/FDP-39/FDP-40 release image | No. | No. | Yes, when digest-bound. | Yes. | `REQUIRED` is expected for bank/prod-style regulated mutation safety. | Only after separate config/release PR. | Fixture profiles and checkpoint barriers are forbidden. | Immutable digest, required checks, runbooks, and rollback plan. | Mutable tag only and missing digest are NO-GO. |
| enablement config PR | No. | No. | Review input only. | Controls release config, not image contents. | Must preserve `REQUIRED` for regulated mutation safety. | Must explicitly request and justify FDP-29 enablement. | Must prove fixture/test code is absent from release image. | Fraud ops, platform, security, rollback, and operator drill evidence. | READY_FOR_ENABLEMENT_REVIEW does not mean PRODUCTION_ENABLED. |

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

## Diagnostic Engine Intelligence

- Producer diagnostic publication is disabled by default with
  `fraud.scoring.events.engine-intelligence.emit-enabled=false`
  (`FRAUD_SCORING_EVENTS_ENGINE_INTELLIGENCE_EMIT_ENABLED=false`).
- Current Velocity v1 diagnostic registration is disabled by default with
  `fraud.scoring.engines.velocity.enabled=false`
  (`FRAUD_SCORING_ENGINE_VELOCITY_ENABLED=false`).
- The emission flag controls publication. The Velocity flag controls whether `velocity.primary / VELOCITY` is
  registered inside the diagnostic runtime after emission is enabled.
- `velocity-v1` is the current version of the independent Velocity diagnostic contract; it is not Rules V1 and is not
  bumped merely because Rules scoring is `rule-based-engine` / `v2`.
- Velocity is diagnostic-only. It cannot authorize payments, block transactions, create cases, alter final decision
  source, change thresholds, or change analyst recommended actions.
- Velocity v1 requires the Feature Enricher recent-transaction window to remain exactly `PT1M`; unsupported windows
  fail startup/configuration or consumer validation rather than silently changing score meaning.
- The current engine-intelligence contract allows three known engine identities: Rules and ML model are required;
  Velocity is an optional third diagnostic engine. Future Device, Merchant, or Graph engines require a versioned
  contract update.

## Rules V2 Input Configuration

- Current Rules scoring is `rule-based-engine` / `v2`; the diagnostic adapter remains `rules.primary` / `2.0.0`.
- Feature Enricher producer windows for Rules and Velocity semantics must remain exactly `PT1M`.
- Rules scoring reads its factual input from the canonical `featureSnapshot` only.
- Removed feature-flag, case-candidate, and top-level duplicate facts are not part of the current Rules V2 score
  input.
- Present-invalid canonical snapshot data fails closed.
- Supported transaction currencies are `PLN`, `EUR`, `USD`, and `GBP`. Unsupported or null currencies are rejected;
  enrichment must not convert unknown currencies with a default rate.
- Historical replay compatibility belongs to explicit event/read compatibility boundaries, not to the current Rules V2
  scoring policy.

## Dependency Posture

- Bouncy Castle `1.85` is an intentional dependency upgrade for the current branch.
- Caffeine is intentionally removed from the active application dependency graph; do not reintroduce Caffeine beans or
  cache-specific configuration without a separate scoped design.

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
