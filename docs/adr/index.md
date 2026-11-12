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
| [Evidence-Gated Regulated Mutation Finalize](evidence_gated_regulated_mutation_finalize.md) | Accepted, current runtime | Regulated mutation finalize lifecycle | Evidence-gated finalize is the only executable regulated mutation model and persisted unsupported versions fail closed. | `Regulated Mutation Regression Gate` | No external finality, distributed ACID, WORM/legal proof, or broker delivery confirmation inside the local transaction |
| [Merchant Source and Occurrence Admission](merchant_source_occurrence_admission.md) | Accepted boundary; runtime disabled | Merchant source authority, typed facts, and exact scoring occurrence | Versioned profile facts separate observed values from admitted evidence; cross-source history needs an authorized identity mapping. | Focused contract, projection, and admission tests | No production source verifier, institutional authority, retention approval, or runtime activation |
| [Attested Feature-History Ordering](feature_history_ordering.md) | Accepted runtime safeguard | Ordering of attested RAW admission and Redis feature-history effects | A durable per-scope/customer gate prevents later history-dependent scoring until the prior admitted event is applied or durably rejected as genuinely late. | Live Mongo/Redis race reproduction and regression; Kafka DLT integration; migration fail-closed test | No production incident claim, exactly-once cross-store transaction, bounded-lateness policy, or deployment readiness |
| [Device Risk Switch Semantics](device_risk_switch_semantics.md) | Accepted domain semantics; runtime disabled | Device history switch counting | Switches count transitions in the complete pre-event window plus the transition into the current occurrence; ambiguous event-time ties remain unavailable. | Focused Mongo integration tests | No authenticated institutional source, calibrated score, or production runtime activation |
| [Device Verified Source Admission](device_verified_source_admission.md) | Repository implementation record; independent review pending | Separate source, Device and coverage authority; epoch-aware ledger; private diagnostic boundary | Current implementation and review gates, including the distinction between test evidence and production approval. | Focused contract, configuration and Mongo/Kafka Testcontainers regressions | No institutional approval, production roles/keys, retention approval, public Device output, or score influence |
| [Device Risk Exact-Payload Delivery Gate](device_risk_exact_payload_delivery_gate.md) | Engineering NO-GO; no production outbox | Device occurrence to enriched Kafka delivery and Redis baseline | Preserve the current disabled admission path until source authenticity, payload retention approval, and exact-payload recovery proofs exist. | Focused fault-injection tests document current enricher ordering; admission guard remains fail-closed | No exact-payload outbox, broker-ack reconciliation, consumer inbox, or cross-system delivery guarantee |
| [FDP-36 Real Chaos Enablement Readiness](fdp_36_real_chaos_enable_readiness.md) | Accepted proof/readiness ADR | Real alert-service process kill proof | Real JVM/process restart proof covers selected durable crash-window states and selected API recovery checks. | `FDP-36 Real Alert-Service Kill Proof` | No production enablement, production certification, KMS/HSM proof, or complete live instruction-boundary chaos |
| [FDP-37 Production Image Chaos Enablement Gate](fdp_37_production_image_chaos_enable_gate.md) | Accepted proof/readiness ADR | Production-like image chaos proof | The release Dockerfile image is killed/restarted against durable state and evidence artifacts bind proof to image identity. | `FDP-37 Production Image Chaos Proof` | No production environment certification, production networking certification, external finality, or bank enablement |
| [FDP-38 Live Runtime Checkpoint Fixture Proof](fdp_38_live_runtime_checkpoint_fixture_proof.md) | Accepted fixture-proof ADR | Live checkpoint fixture chaos | Live checkpoint proof uses a dedicated test-fixture image and must not be promoted as release-image proof. | `FDP-38 Live Runtime Checkpoint Chaos Proof` | No release image proof, production deployability, production certification, or full instruction-boundary coverage |
| [FDP-39 Release Artifact Separation Governance](fdp_39_release_artifact_separation_governance.md) | Accepted release governance ADR | Release artifact separation | FDP-38 fixture artifacts and release image artifacts are separated, with claims tied to provenance. | `FDP-39 Release Governance Gate` | No runtime mutation semantic change, alternate model fallback, production enablement, or fixture promotion |
| [FDP-40 Platform Release Controls and Signed Provenance Readiness](fdp_40_platform_release_controls_signed_provenance_readiness.md) | Accepted readiness ADR | Platform release controls readiness | Release controls define evidence shape for future enablement review, but external platform enforcement remains outside this repo proof. | `FDP-40 Release Controls` | No default real cosign verification, registry immutability enforcement, branch protection API verification, or production certification |

## Maintenance Rules

- Add a new ADR only for decisions that outlive one branch.
- Keep branch execution detail in branch docs or proof packs, not in ADRs.
- If a later branch supersedes an ADR, update this index with the new interpretation instead of rewriting the old decision.
- Every ADR that mentions readiness or production-adjacent proof must include explicit non-claims.
