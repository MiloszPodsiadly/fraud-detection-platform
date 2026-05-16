# Documentation Index

Status: current documentation index.

## Scope

This index is the entry point for current documentation. It separates current source-of-truth docs from FDP branch
evidence so reviewers can find the latest interpretation without treating older branch records as current design.

## Current Source Of Truth

These files describe the current intended interpretation after merge. Start here before reading branch-level
FDP evidence.

- [Current architecture](architecture/current_architecture.md)
- [Architecture documentation](architecture/index.md)
- [Architecture decision records](adr/index.md)
- [Fraud case management architecture](architecture/fraud_case_management_architecture.md)
- [Product documentation](product/index.md)
- [Fraud Case Management](product/fraud_case_management.md)
- [API documentation](api/index.md)
- [Public API semantics](api/public_api_semantics.md)
- [Fraud Case API](api/fraud_case_api.md)
- [Status truth table](api/status_truth_table.md)
- [OpenAPI safety audit](api/openapi_safety_audit.md)
- [Configuration documentation](configuration/index.md)
- [Configuration guide](configuration/configuration_guide.md)
- [Deployment documentation](deployment/index.md)
- [Architecture diagrams](architecture/diagrams.md)
- [Documentation assets](assets/index.md)
- [Security documentation](security/index.md)
- [Observability documentation](observability/index.md)
- [Frontend documentation](frontend/index.md)
- [ML documentation](ml/index.md)

## Current API, Config, And Security Semantics

These files summarize current externally visible behavior and configuration posture. They are current contract
summaries, not branch proof packs.

- [Public API semantics](api/public_api_semantics.md)
- [Status truth table](api/status_truth_table.md)
- [Configuration documentation](configuration/index.md)
- [Configuration guide](configuration/configuration_guide.md)
- [Deployment documentation](deployment/index.md)
- [Security architecture](security/security_architecture.md)
- [Endpoint authorization map](security/endpoint_authorization_map.md)
- [Internal service identity](security/internal_service_identity.md)

## Frontend And ML Semantics

- [Frontend documentation](frontend/index.md)
- [Frontend API client boundary](frontend/api_client_boundary.md)
- [ML documentation](ml/index.md)
- [ML governance and drift](ml/ml_governance_drift_v1.md)

## Documentation Governance

These files define how documentation is classified, named, linked, and reviewed.

- [FDP branch evidence](fdp/index.md)
- [FDP branch index](fdp/branch_index.md)
- [CI evidence map](ci_evidence_map.md)
- [Reviewer checklist](reviewer_checklist.md)
- [CI consolidation plan](ci_consolidation_plan.md)
- [Documentation inventory](documentation_inventory.md)
- [Documentation audit](documentation_audit.md)
- [Documentation style guide](documentation_style_guide.md)
- [Documentation naming map](documentation_naming_map.md)
- [Documentation cleanup merge gate](documentation_cleanup_merge_gate.md)

## API Contracts

- [API documentation](api/index.md)
- [OpenAPI specification index](openapi/index.md)
- [Alert service OpenAPI](openapi/alert_service.openapi.yaml)
- [ML inference service OpenAPI](openapi/ml_inference_service.openapi.yaml)
- [Fraud Case API](api/fraud_case_api.md)
- [API surface v1](api/api_surface_v1.md)
- [API error contract](api/api_error_contract.md)

## Operational Docs

- [Runbook index](runbooks/index.md)
- [Alert service operations](runbooks/alert_service_operations.md)
- [Regulated mutation recovery](runbooks/regulated_mutation_recovery.md)
- [Regulated mutation drills](runbooks/regulated_mutation_drills.md)
- [Fraud case operations](runbooks/fraud_case_operations.md)
- [Operations evidence index](ops/index.md)
- [Operations documentation](operations/index.md)
- [Observability documentation](observability/index.md)

## Product Docs

- [Product documentation](product/index.md)
- [Fraud Case Management](product/fraud_case_management.md)

## FDP Branch Evidence

FDP release documents are retained as branch evidence. Treat each FDP document as evidence for that branch unless
a current document explicitly says otherwise.

Start with [FDP branch evidence](fdp/index.md), then use [FDP branch index](fdp/branch_index.md) for branch-level navigation and
[FDP evidence status](fdp/evidence_status.md) for current interpretation rules. Use
[CI evidence map](ci_evidence_map.md) for current job names and replacement mappings.

The FDP-35 through FDP-40 documents are proof, readiness, or governance evidence. They are not production
enablement and they are not bank certification.

FDP-42 fraud case management is a local audited lifecycle workflow. It is not evidence-gated finalize, not a
regulated mutation finality claim, not lease-fenced replay safety, and not external finality.

FDP-43 adds shared idempotency primitives and local fraud-case lifecycle retry safety. It does not route lifecycle
POSTs through `RegulatedMutationCoordinator` and does not claim FDP-29 finalize, lease fencing, global exactly-once,
distributed ACID, or external finality.

FDP-38 fixture proof is test-fixture runtime evidence only. It is not production-image proof.

FDP-40 signed provenance readiness is readiness evidence only. It is not enforced signing unless a real
sign-and-verify artifact or platform policy proves enforcement.

- [FDP branch evidence](fdp/index.md)
- [FDP branch index](fdp/branch_index.md)
- [FDP evidence status](fdp/evidence_status.md)

## Release And Governance Proof Artifacts

Release/governance artifacts are evidence for review posture. They do not enable production and they do not prove
business correctness.

- [Architecture decision records](adr/index.md)
- [Release documentation](release/index.md)
- [Release governance](release/release_governance.md)
- [FDP-39 release artifact separation governance](adr/fdp_39_release_artifact_separation_governance.md)
- [FDP-40 platform release controls readiness](adr/fdp_40_platform_release_controls_signed_provenance_readiness.md)
- [FDP-40 required checks matrix](release/fdp_40_required_checks_matrix.md)

## Templates And Checklists

Templates and checklists are forms for future review. Placeholder fields in templates are not generated proof.

- [FDP-36 enablement decision checklist](fdp/fdp_36_enablement_decision_checklist.md)
- [FDP-37 enablement decision checklist](fdp/fdp_37_enablement_decision_checklist.md)
- [FDP-37 observability thresholds](ops/fdp_37_dashboard_and_alert_thresholds.md)
- [FDP-37 rollback validation output template](ops/fdp_37_rollback_validation_output_template.md)
- [FDP-40 branch protection readiness](release/fdp_40_branch_protection_readiness.md)

## Interpretation Order

When branch evidence conflicts with current source-of-truth docs, use current source-of-truth docs first, then
later FDP evidence, then earlier branch evidence.

`READY_FOR_ENABLEMENT_REVIEW` never means `PRODUCTION_ENABLED`.

## Non-Claims

This repository does not claim bank certification, production enablement, legal notarization, WORM storage,
distributed ACID, exactly-once Kafka delivery, or external finality unless a document names a concrete
implemented control and its remaining limitations.



