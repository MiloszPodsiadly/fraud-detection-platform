# Documentation Index

Status: current documentation index.

## Scope

This index is the entry point for current documentation. It separates evergreen docs from historical FDP proof
artifacts so reviewers can find the latest source of truth without losing branch-level traceability.

## Current State

- [Current architecture](architecture/current-architecture.md)
- [Documentation inventory](documentation-inventory.md)
- [Documentation audit](documentation-audit.md)
- [Documentation style guide](documentation-style-guide.md)
- [Documentation naming map](documentation-naming-map.md)
- [Public API semantics](api/public-api-semantics.md)
- [Status truth table](api/status-truth-table.md)
- [OpenAPI safety audit](api/openapi-safety-audit.md)
- [Configuration guide](configuration/configuration-guide.md)
- [Architecture diagrams](architecture/diagrams.md)
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

## Release And Proof Docs

FDP release documents are retained for historical traceability. Later FDP branches may supersede parts of
earlier scope. Treat each FDP document as evidence for that branch unless a current document explicitly says
otherwise.

- [FDP-40 merge gate](fdp-40-merge-gate.md)
- [FDP-39 merge gate](fdp-39-merge-gate.md)
- [FDP-38 merge gate](fdp-38-merge-gate.md)
- [FDP-37 merge gate](fdp-37-merge-gate.md)
- [FDP-36 merge gate](fdp-36-merge-gate.md)
- [FDP-35 merge gate](fdp-35-merge-gate.md)

## Non-Claims

This repository does not claim bank certification, production enablement, legal notarization, WORM storage,
distributed ACID, exactly-once Kafka delivery, or external finality unless a document names a concrete
implemented control and its remaining limitations.



