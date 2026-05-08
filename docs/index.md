# Documentation Index

Status: current documentation index.

## Scope

This index is the entry point for current documentation. It separates evergreen docs from historical FDP proof
artifacts so reviewers can find the latest source of truth without losing branch-level traceability.

## Current Source Of Truth

These files describe the current intended interpretation after merge. Start here before reading branch-level
FDP evidence.

- [Current architecture](architecture/current-architecture.md)
- [Public API semantics](api/public-api-semantics.md)
- [Status truth table](api/status-truth-table.md)
- [OpenAPI safety audit](api/openapi-safety-audit.md)
- [Configuration guide](configuration/configuration-guide.md)
- [Architecture diagrams](architecture/diagrams.md)

## Current API, Config, And Security Semantics

These files summarize current externally visible behavior and configuration posture. They are current contract
summaries, not historical proof packs.

- [Public API semantics](api/public-api-semantics.md)
- [Status truth table](api/status-truth-table.md)
- [Configuration guide](configuration/configuration-guide.md)
- [Security foundation](security/security-foundation-v1.md)

## Documentation Governance

These files define how documentation is classified, named, linked, and reviewed.

- [Documentation inventory](documentation-inventory.md)
- [Documentation audit](documentation-audit.md)
- [Documentation style guide](documentation-style-guide.md)
- [Documentation naming map](documentation-naming-map.md)
- [Documentation cleanup merge gate](documentation-cleanup-merge-gate.md)

## API Contracts

- [Alert service OpenAPI](openapi/alert-service.openapi.yaml)
- [ML inference service OpenAPI](openapi/ml-inference-service.openapi.yaml)
- [API surface v1](api/api-surface-v1.md)
- [API error contract](api/api-error-contract.md)

## Operational Docs

- [Alert service production runbooks](runbooks/alert-service-production-runbooks.md)
- [FDP-29 recovery required runbook](runbooks/fdp-29-finalize-recovery-required.md)
- [FDP-33 lease renewal runbook](runbooks/fdp-33-lease-renewal-runbook.md)
- [FDP-34 checkpoint renewal runbook](runbooks/fdp-34-safe-checkpoint-renewal-runbook.md)
- [FDP-35 recovery drill runbook](runbooks/fdp-35-regulated-mutation-recovery-drill-runbook.md)
- [FDP-36 real chaos recovery drill runbook](runbooks/fdp-36-real-chaos-recovery-drill-runbook.md)

## Historical FDP Evidence

FDP release documents are retained for historical traceability. Later FDP branches may supersede parts of
earlier scope. Treat each FDP document as evidence for that branch unless a current document explicitly says
otherwise.

The FDP-35 through FDP-40 documents are proof, readiness, or governance evidence. They are not production
enablement and they are not bank certification.

FDP-38 fixture proof is test-fixture runtime evidence only. It is not production-image proof.

FDP-40 signed provenance readiness is readiness evidence only. It is not enforced signing unless a real
sign-and-verify artifact or platform policy proves enforcement.

- [FDP-40 merge gate](fdp-40-merge-gate.md)
- [FDP-39 merge gate](fdp-39-merge-gate.md)
- [FDP-38 merge gate](fdp-38-merge-gate.md)
- [FDP-37 merge gate](fdp-37-merge-gate.md)
- [FDP-36 merge gate](fdp-36-merge-gate.md)
- [FDP-35 merge gate](fdp-35-merge-gate.md)

## Release And Governance Proof Artifacts

Release/governance artifacts are evidence for review posture. They do not enable production and they do not prove
business correctness.

- [FDP-39 release artifact separation governance](adr/fdp-39-release-artifact-separation-governance.md)
- [FDP-40 platform release controls readiness](adr/fdp-40-platform-release-controls-signed-provenance-readiness.md)
- [FDP-40 required checks matrix](release/fdp-40-required-checks-matrix.md)

## Templates And Checklists

Templates and checklists are forms for future review. Placeholder fields in templates are not generated proof.

- [FDP-36 enablement decision checklist](fdp-36-enablement-decision-checklist.md)
- [FDP-37 enablement decision checklist](fdp-37-enablement-decision-checklist.md)
- [FDP-40 branch protection readiness](release/fdp-40-branch-protection-readiness.md)

## Superseded Or Historical Context

Earlier FDP documents may be superseded by later proof branches or current source-of-truth docs. When there is a
conflict, use current source-of-truth docs first, then later FDP evidence, then earlier historical evidence.

`READY_FOR_ENABLEMENT_REVIEW` never means `PRODUCTION_ENABLED`.

## Non-Claims

This repository does not claim bank certification, production enablement, legal notarization, WORM storage,
distributed ACID, exactly-once Kafka delivery, or external finality unless a document names a concrete
implemented control and its remaining limitations.



