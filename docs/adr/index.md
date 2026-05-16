# Architecture Decision Records

Status: current ADR navigation index.

ADR files are kept as separate decision records. Do not merge them into one narrative document: each ADR preserves
the decision, scope, evidence boundary, and non-claims from the branch that introduced it. Use this index to find
the current interpretation, then read the individual ADR for the original decision context.

## How To Read ADRs

- Current behavior is interpreted through `docs/index.md`, `docs/architecture/current_architecture.md`, and `docs/api/public_api_semantics.md`.
- Branch-level proof is indexed in `docs/fdp/branch_index.md`.
- Current CI proof and replacement job names are indexed in `docs/ci_evidence_map.md`.
- ADR wording remains branch evidence; later FDP branches may narrow, supersede, or operationalize parts of it.

## ADR Index

| ADR | Status | Decision area | Current interpretation | Current proof gate | Non-claims |
| --- | --- | --- | --- | --- | --- |
| [FDP-29 Local Evidence-Precondition-Gated Finalize](fdp_29_evidence_gated_finalize.md) | Accepted, feature-flagged scope | Regulated mutation finalize lifecycle | Local evidence-precondition-gated finalize exists only for the scoped submit-decision path and remains disabled by default unless explicitly configured. | `Regulated Mutation Regression Gate` | No external finality, distributed ACID, WORM/legal proof, or broker delivery confirmation inside the local transaction |
| [FDP-36 Real Chaos Enablement Readiness](fdp_36_real_chaos_enable_readiness.md) | Accepted proof/readiness ADR | Real alert-service process kill proof | Real JVM/process restart proof covers selected durable crash-window states and selected API recovery checks. | `FDP-36 Real Alert-Service Kill Proof` | No production enablement, production certification, KMS/HSM proof, or complete live instruction-boundary chaos |
| [FDP-37 Production Image Chaos Enablement Gate](fdp_37_production_image_chaos_enable_gate.md) | Accepted proof/readiness ADR | Production-like image chaos proof | The release Dockerfile image is killed/restarted against durable state and evidence artifacts bind proof to image identity. | `FDP-37 Production Image Chaos Proof` | No production environment certification, production networking certification, external finality, or bank enablement |
| [FDP-38 Live Runtime Checkpoint Fixture Proof](fdp_38_live_runtime_checkpoint_fixture_proof.md) | Accepted fixture-proof ADR | Live checkpoint fixture chaos | Live checkpoint proof uses a dedicated test-fixture image and must not be promoted as release-image proof. | `FDP-38 Live Runtime Checkpoint Chaos Proof` | No release image proof, production deployability, production certification, or full instruction-boundary coverage |
| [FDP-39 Release Artifact Separation Governance](fdp_39_release_artifact_separation_governance.md) | Accepted release governance ADR | Release artifact separation | FDP-38 fixture artifacts and release image artifacts are separated, with claims tied to provenance. | `FDP-39 Release Governance Gate` | No runtime mutation semantic change, production enablement, fixture promotion, or automatic FDP-29 production mode |
| [FDP-40 Platform Release Controls and Signed Provenance Readiness](fdp_40_platform_release_controls_signed_provenance_readiness.md) | Accepted readiness ADR | Platform release controls readiness | Release controls define evidence shape for future enablement review, but external platform enforcement remains outside this repo proof. | `FDP-40 Release Controls` | No default real cosign verification, registry immutability enforcement, branch protection API verification, or production certification |

## Maintenance Rules

- Add a new ADR only for decisions that outlive one branch.
- Keep branch execution detail in branch docs or proof packs, not in ADRs.
- If a later branch supersedes an ADR, update this index with the new interpretation instead of rewriting the old decision.
- Every ADR that mentions readiness or production-adjacent proof must include explicit non-claims.
