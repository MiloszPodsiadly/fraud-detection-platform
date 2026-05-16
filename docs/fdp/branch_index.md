# FDP Branch Index

Status: current branch evidence navigation index.

Use this index to find durable evidence without copying full branch READMEs into new docs. It lists FDP branches
with retained documentation in this repository; missing FDP numbers are not inferred. Older entries remain branch
evidence, while current behavior is interpreted through `docs/index.md`, `docs/architecture/current_architecture.md`,
and `docs/ci_evidence_map.md`.

## Branch Map

| FDP | Branch name | Category | Main claim | Key files/docs | Main CI gate | Safety guarantees | Non-goals |
| --- | --- | --- | --- | --- | --- | --- | --- |
| FDP-25 | Regulated mutation commit | backend / audit | Local regulated mutation evidence is bounded and named. | `docs/fdp/fdp_25_regulated_mutation_commit.md` | Branch evidence; later covered by `Regulated Mutation Regression Gate` | Explicit local audit/evidence limits | No external finality or WORM/legal proof |
| FDP-27 | Merge gate and transactional outbox closure | backend / governance | Regulated mutation merge gate names local guarantees and non-claims. | `docs/fdp/fdp_27_merge_gate.md`, `docs/architecture/alert_service_source_of_truth.md` | Branch evidence; later covered by `Regulated Mutation Regression Gate` | Local transaction/outbox safety language | No distributed ACID or exactly-once Kafka |
| FDP-28 | Invariant proof and Docker-backed integration | backend / integration | Invariant proof requires Docker/Testcontainers evidence. | `docs/fdp/fdp_28_invariant_proof_report.md` | `FDP-28 Docker Integration Proof` | Integration proof, failure-injection groups | No local-only proof substitution |
| FDP-28B | Chaos handoff | backend / chaos | Chaos proof was separated from invariant proof. | `docs/fdp/fdp_28b_chaos_handoff.md` | Branch handoff; later FDP-35 through FDP-38 gates | Clear handoff into chaos proof branches | No production-image proof by itself |
| FDP-29 | Evidence-gated finalize design and implementation | backend / mutation | Local evidence-precondition finalize behavior is explicit and limited. | `docs/adr/fdp_29_evidence_gated_finalize.md`, `docs/fdp/fdp_29_design_checklist.md`, `docs/fdp/fdp_29_evidence_gated_finalize_handoff.md`, `docs/fdp/fdp_29_implementation_merge_gate.md` | `Regulated Mutation Regression Gate` | Local finalize recovery and evidence-state regression proof | No external witness finality inside local transaction |
| FDP-30 | Executor split | backend / lifecycle | Executor responsibilities are separated for regulated mutation execution. | `docs/fdp/fdp_30_executor_split.md` | `Regulated Mutation Regression Gate` | Execution lifecycle separation | No new mutation semantics outside scoped executor work |
| FDP-31 | Claim replay policy extraction | backend / replay | Claim/replay policy is extracted and named. | `docs/fdp/fdp_31_claim_replay_policy_extraction.md` | `Regulated Mutation Regression Gate` | Replay policy traceability | No lease fencing by itself |
| FDP-32 | Lease fencing and stale worker protection | backend / consistency | Stale workers are blocked by lease-owner fencing. | `docs/fdp/fdp_32_lease_fencing_stale_worker_protection.md`, `docs/fdp/fdp_32_merge_gate.md` | `Regulated Mutation Regression Gate` | Active lease validation before mutation | No distributed lock or external finality |
| FDP-33 | Lease renewal operational readiness | backend / operations | Lease renewal behavior is observable and operationally bounded. | `docs/fdp/fdp_33_lease_renewal_operational_readiness.md`, `docs/fdp/fdp_33_merge_gate.md`, `docs/runbooks/regulated_mutation_recovery.md` | `Regulated Mutation Regression Gate` | Renewal and failure-mode proof | No production enablement |
| FDP-34 | Safe checkpoint adoption | backend / checkpoint | Safe checkpoint renewal rules are documented and gated. | `docs/fdp/fdp_34_merge_gate.md`, `docs/architecture/regulated_mutation_safe_checkpoints.md`, `docs/runbooks/regulated_mutation_recovery.md` | `Regulated Mutation Regression Gate` | Checkpoint lifecycle regression proof | No external platform guarantee |
| FDP-35 | Production readiness proof | backend / readiness | Recovery and readiness proof is evidence, not enablement. | `docs/fdp/fdp_35_merge_gate.md`, `docs/testing/fdp_35_regulated_mutation_readiness_proof.md`, `docs/runbooks/regulated_mutation_drills.md` | `FDP-35 Production Readiness Proof` | Recovery/e2e/readiness groups | No production certification |
| FDP-36 | Real alert-service kill proof | backend / chaos | Real service kill proof is gated and separated from enablement. | `docs/adr/fdp_36_real_chaos_enable_readiness.md`, `docs/fdp/fdp_36_merge_gate.md`, `docs/fdp/fdp_36_enablement_decision_checklist.md`, `docs/runbooks/regulated_mutation_drills.md` | `FDP-36 Real Alert-Service Kill Proof` | Real kill recovery proof | No automatic bank enablement |
| FDP-37 | Production-image chaos proof | backend / chaos | Production image chaos proof uses packaged evidence. | `docs/adr/fdp_37_production_image_chaos_enable_gate.md`, `docs/fdp/fdp_37_merge_gate.md`, `docs/fdp/fdp_37_enablement_decision_checklist.md`, `docs/testing/fdp_37_production_image_chaos_proof.md`, `docs/ops/fdp_37_dashboard_and_alert_thresholds.md`, `docs/ops/fdp_37_rollback_validation_output_template.md` | `FDP-37 Production Image Chaos Proof` | Image proof artifacts and reports | No live production-image instruction-boundary proof |
| FDP-38 | Live runtime checkpoint chaos proof | backend / chaos | Live fixture runtime checkpoint proof is explicit. | `docs/fdp/fdp_38_merge_gate.md`, `docs/testing/fdp_38_live_runtime_checkpoint_proof.md`, `docs/adr/fdp_38_live_runtime_checkpoint_fixture_proof.md` | `FDP-38 Live Runtime Checkpoint Chaos Proof` | Runtime-reached fixture proof | No production-image live in-flight proof |
| FDP-39 | Release artifact separation governance | release / governance | Release artifact separation and approval evidence are named. | `docs/fdp/fdp_39_merge_gate.md`, `docs/adr/fdp_39_release_artifact_separation_governance.md`, `docs/release/fdp_39_*` | `FDP-39 Release Governance Gate` | Release artifact separation checks | No production enablement or fixture promotion |
| FDP-40 | Platform release controls readiness | release / governance | Platform controls are readiness evidence with external gaps named. | `docs/fdp/fdp_40_merge_gate.md`, `docs/adr/fdp_40_platform_release_controls_signed_provenance_readiness.md`, `docs/release/fdp_40_*` | `FDP-40 Release Controls` | Required checks and readiness matrices | No enforced external platform policy by itself |
| FDP-42 | Fraud case management lifecycle | backend / product | Fraud case lifecycle is locally audited and role-gated. | `docs/fdp/fdp_42_summary.md`, `docs/fdp/fdp_42_merge_gate.md`, `docs/product/fraud_case_management.md` | `FDP-42 Fraud Case Management` | Lifecycle policy, audit, security tests | No regulated mutation finality claim |
| FDP-43 | Fraud case idempotency | backend / idempotency | Shared idempotency primitives protect fraud-case lifecycle retries. | `docs/fdp/fdp_43_merge_gate.md`, `docs/runbooks/fraud_case_operations.md` | `FDP-43 Fraud Case Idempotency` | Idempotency conflict/concurrency/failure proof | No global exactly-once or lease fencing |
| FDP-44 | Fraud case idempotency hardening | backend / idempotency | Replay snapshot behavior and retention limits are hardened. | `docs/fdp/fdp_44_merge_gate.md` | `FDP-44 Fraud Case Idempotency Hardening` | Replay equivalence and retention proof | No deterministic concurrent response timing |
| FDP-45 | Fraud case work queue read model | backend / read model | Work queue reads are bounded, audited, and cursor-safe. | `docs/fdp/fdp_45_work_queue_readiness.md`, `docs/runbooks/fraud_case_operations.md` | `FDP-45 Fraud Case Work Queue Read Model` | Read model, cursor, audit, OpenAPI proof | No lifecycle mutation workflow |
| FDP-46 | Fraud case work queue UI and scored filtering | frontend / read model | Analyst console work queue and scored filtering stay read-only and bounded. | `docs/fdp/fdp_46_fraud_case_work_queue_ui.md` | `Frontend Build` and `Analyst Console Product Gate` | Frontend baseline, backend scored-search JUnit verification | No export, bulk action, assignment, or enumeration support |
| FDP-47 | Analyst console UX and summary | frontend / UX | Summary count is separate from work queue pagination. | `docs/fdp/fdp_47_analyst_console_ux_and_summary.md` | `Analyst Console Product Gate` | Backend summary tests, frontend summary tests, UI build | No snapshot consistency claim |
| FDP-48 | BFF/session/request lifecycle hardening | security / runtime | BFF session and request lifecycle are explicit and tested. | `docs/fdp/fdp_48_branch_readme.md`, `scripts/check-fdp48-api-client-boundary.mjs` | `FDP-48 BFF Session & Request Lifecycle` | BFF lifecycle tests, API boundary guard, skip guard | No enterprise IAM or new business endpoints |
| FDP-49 | Security route boundary decomposition | security | Route ownership and matcher order are guarded. | `docs/security/endpoint_authorization_map.md`, `scripts/check-fdp49-api-client-boundary.mjs` | `FDP-49 Security Route Boundary Hardening` | Route boundary tests, frontend API boundary guard | No frontend authorization enforcement |
| FDP-50 | Frontend API client boundary | frontend / security | Auth-sensitive UI uses explicit API clients and legacy API routes stay removed. | `docs/fdp/fdp_50_frontend_api_client_boundary.md`, `scripts/check-fdp50-scope.mjs` | `Analyst Console Frontend Architecture Gate` | Raw fetch/default wrapper/scope guard coverage | No backend lifecycle or product workflow expansion |
| FDP-51 | Workspace runtime provider | frontend / runtime | Workspace runtime context owns session-bound API client and capability state. | `docs/fdp/fdp_51_workspace_runtime_provider.md`, `scripts/check-fdp51-scope.mjs` | `Analyst Console Frontend Architecture Gate` | Runtime provider tests, stale-state guards, scope guard | No backend auth or mutation semantics |
| FDP-52 | Workspace split and case detail UX | frontend / UX | Detail UX and workspace containers are runtime consumers with stale-state protection. | `docs/fdp/fdp_52_workspace_detail_ux.md`, `scripts/check-fdp52-scope.mjs` | `Analyst Console Frontend Architecture Gate` | Detail/router/container tests, scope guard | No assignment, claim, bulk, export, or backend changes |
| FDP-53 | Workspace runtime ownership | frontend / runtime | Workspace-specific runtimes own reads; shell and registry stay thin. | `docs/fdp/fdp_53_workspace_runtime_ownership.md`, `scripts/check-fdp53-scope.mjs` | `Analyst Console Frontend Architecture Gate` | Runtime ownership tests, no-duplicate-fetch guard, scope guard | No speculative prefetch or auth mode changes |
| FDP-54 | CI evidence and documentation simplification | CI-docs | CI/docs evidence is indexed and related CI proof is consolidated without making checks advisory. | `docs/ci_evidence_map.md`, `docs/ci_consolidation_plan.md`, `docs/reviewer_checklist.md` | `FDP-54 CI Docs Governance`, `Analyst Console Product Gate`, and `Analyst Console Frontend Architecture Gate` | Evidence map comparison, docs overclaim guard, preserved FDP-46/FDP-47 and FDP-50 through FDP-53 tests | No product code or behavior changes |

## How To Write Future FDP Docs

- Branch README explains only what changed in that branch.
- Long-lived architectural rules move to central docs.
- Link to `docs/ci_evidence_map.md` for CI proof.
- Link to `docs/reviewer_checklist.md` for review process.
- Avoid duplicating the same NO MERCY gate across several files.
- Avoid production posture claims unless the exact evidence and limitation are named.

## What Belongs In Branch README Vs Central Docs

Branch README:
- Branch scope.
- Non-goals.
- Changed areas.
- Merge gate.
- Risk summary.

Central docs:
- CI evidence model.
- Architecture decisions.
- Reviewer checklist.
- Reusable safety policy.
- Long-lived branch governance.
