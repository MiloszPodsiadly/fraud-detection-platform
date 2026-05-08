# Documentation Audit

Status: current documentation audit.

## Scope

This audit defines how repository documentation is classified, reviewed, and interpreted. It covers project
documentation in `index.md`, `docs/**`, `.github/PULL_REQUEST_TEMPLATE/**`, and `deployment/service-identity/**`.
It excludes prompt sources in `documents/`, build output, dependency folders, git internals, and temporary folders.

The audit is documentation-only. It does not change runtime semantics, public API contracts, release approval,
production enablement, or bank certification posture.

## Classification

| Class | Meaning | Examples | Review rule |
| --- | --- | --- | --- |
| Current source of truth | Stable docs that should remain true after merge. | `docs/index.md`, current architecture, public API semantics | Must avoid active-branch wording and must state non-claims clearly. |
| Contract summary | Human-readable companion to code/API contracts. | `docs/api/status-truth-table.md`, `docs/configuration/configuration-guide.md` | Must match enum/config/controller behavior or explicitly call out gaps. |
| Historical FDP evidence | Branch-specific proof, handoff, merge-gate, or readiness documents. | `docs/FDP-*.md`, `docs/**/FDP-*.md` | Preserve for traceability; do not treat as current truth when superseded. |
| Template or checklist | A form to complete during release or enablement review. | PR templates, enablement checklists, release owner templates | Placeholders are allowed only when clearly marked as fields to fill, not generated proof. |
| Supporting local docs | Local setup or service identity material. | `deployment/service-identity/index.md` | Must label local/dev material as local/dev and avoid production claims. |

## Current Sources Of Truth

- [Documentation index](index.md)
- [Current architecture](architecture/current-architecture.md)
- [Public API semantics](api/public-api-semantics.md)
- [API status truth table](api/status-truth-table.md)
- [Configuration guide](configuration/configuration-guide.md)
- [Runbook standards](runbooks/index.md)
- [Documentation style guide](documentation-style-guide.md)
- [Historical FDP index](historical-fdp-documents.md)

## Implementation Alignment Checks

- Public submit-decision statuses are checked against `SubmitDecisionOperationStatus`.
- Public API status wording must distinguish local evidence, recovery-required states, and states that are not
  external finality.
- Configuration wording must distinguish local/dev, fixture/test-only, production-like, and bank profile behavior.
- Markdown links are checked across repository documentation.
- JSON documentation artifacts must parse.
- Current documentation filenames use lower-kebab names under domain folders.
- Current docs must not embed an active branch name as their status.
- Inventory must exclude prompts, build outputs, dependency folders, git internals, and temporary folders.

## Known Honest Limitations

- Historical FDP documents can contain older scope, older risk statements, or template placeholders.
- Historical FDP documents are retained as trace evidence and are not automatically rewritten into current truth.
- Long root `index.md` sections remain as accumulated project history; current docs should link out to domain docs
  instead of expanding that file further.
- Documentation tests guard wording and links; they are not architectural proof by themselves.

## Non-Claims

This audit does not claim production enablement, bank certification, external finality, WORM storage, legal
notarization, distributed ACID, exactly-once Kafka delivery, regulator approval, or release approval.
