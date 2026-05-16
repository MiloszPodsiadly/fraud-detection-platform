# Documentation Style Guide

Status: current documentation style guide.

## Scope

This guide defines naming and readability rules for repository documentation. It does not rewrite branch
evidence or change runtime behavior.

## File Naming

Use these conventions for repository documentation. The default for documentation is domain-first `snake_case`
naming. Branch identifiers such as `FDP-40` may appear in document text when they are trace evidence, but filenames
use lowercase underscore form such as `fdp_40_release_controls.md`.

| Document type | Location | Naming |
| --- | --- | --- |
| Current index docs | `docs/` | `snake_case.md` |
| API docs | `docs/api/` | `snake_case.md` |
| Architecture docs | `docs/architecture/` | `snake_case.md` |
| Configuration docs | `docs/configuration/` | `snake_case.md` |
| Security docs | `docs/security/` | `snake_case.md` |
| Observability docs | `docs/observability/` | `snake_case.md` |
| ML docs | `docs/ml/` | `snake_case.md` |
| Runbooks | `docs/runbooks/` | `snake_case.md` |
| FDP branch evidence | `docs/fdp/` | `fdp_*` snake_case filenames, with branch-evidence status in the content |
| ADRs from FDP branches | `docs/adr/` | May keep `fdp_*` filenames when the decision record is branch-origin trace evidence |
| Release, testing, and runbook evidence | Domain folder | May keep `fdp_*` filenames only when the branch id is part of the evidence record |
| OpenAPI specs | `docs/openapi/` | Service-oriented `snake_case.openapi.yaml` |
| JSON/YAML templates | Domain folder | Preserve machine-readable names when scripts depend on them |

`README.md` is allowed only at repository or external tool boundaries where platform convention expects it.
Documentation under `docs/` uses `index.md` for directory indexes. New docs must not introduce mixed `PascalCase`,
`SCREAMING-CASE`, camelCase, kebab-case, or space-separated names. New current implementation docs must not be named
`fdp_*` unless they are branch evidence, ADR trace records, release proof, testing proof, runbook evidence, or a
template whose branch identifier is part of its purpose.

## FDP Branch Evidence

FDP branch artifacts keep their branch identifier in the title, body, and filename. If a branch document is edited,
add status/scope clarification rather than pretending the original risk never existed.

## Audit Rule

Current docs should remain true after merge. Avoid phrases that bind a current document to an active branch or
temporary task. If a document is branch-specific, classify it as FDP branch evidence or a release template.

## Writing Style

- Prefer short paragraphs.
- Keep current source-of-truth Markdown lines under 180 characters outside code blocks, tables, and unavoidable
  URLs.
- Use `Status`, `Scope`, and `Non-claims` sections for large or review-facing docs.
- State limitations directly.
- Do not describe future behavior as current behavior.
- Do not claim production enablement, bank certification, WORM storage, legal notarization, external finality,
  distributed ACID, or exactly-once Kafka unless concrete implemented evidence exists.

## Link Style

- Prefer relative Markdown links.
- Link to current indexes before deep branch-evidence files.
- Keep branch-evidence links valid after any documentation move.


