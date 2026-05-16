# Documentation Assets Index

Status: current documentation assets index.

## Scope

This folder contains repository-owned diagram assets used by README and architecture documentation. Assets are current
documentation sources when linked from current docs. They are not generated build artifacts and should remain reviewed
with the documentation they support.

## Current Diagram Assets

| Asset | Used by | Scope |
| --- | --- | --- |
| [README architecture](readme_architecture.svg) | README, architecture diagrams | Service boundaries, event pipeline, intelligence services, state, evidence, and observability. |
| [README runtime flow](readme_runtime_flow.svg) | README, architecture diagrams | Runtime transaction flow from ingest through analyst workflow and audit evidence. |
| [README security and audit](readme_security_audit.svg) | README | Identity modes, backend enforcement, audit evidence, and non-claims. |
| [Regulated mutation lifecycle](architecture_regulated_mutation_lifecycle.svg) | Architecture diagrams | Local command state, durable evidence, finalize, confirmation, and recovery paths. |
| [Claim replay checkpoint flow](architecture_claim_replay_checkpoint.svg) | Architecture diagrams | Idempotency, replay, lease fencing, renewal, and checkpoint behavior. |
| [Release governance flow](architecture_release_governance.svg) | Architecture diagrams | CI proof, digest evidence, manifest review, platform controls, and enablement boundary. |
| [Alert to case roadmap](architecture_alert_case_roadmap.svg) | Architecture diagrams | Current alert/case workflow and explicitly marked future hardening. |

## Maintenance Rules

- Keep `<title>` and `<desc>` in each SVG because the diagrams are embedded as images in Markdown.
- Keep labels bounded and reviewable; do not include secrets, IDs, tokens, or environment-specific values.
- Update [architecture diagrams](../architecture/diagrams.md) when adding or removing architecture assets.
- Update README when changing the three README-facing diagrams.
