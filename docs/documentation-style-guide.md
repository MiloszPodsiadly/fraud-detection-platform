# Documentation Style Guide

Status: current documentation style guide.

## Scope

This guide defines naming and readability rules for repository documentation. It does not rewrite historical
evidence or change runtime behavior.

## File Naming

Use these conventions for repository documentation. The default is `lower-kebab`; branch identifiers such as
`FDP-40` may appear in document text, but filenames use lowercase `fdp-40-*` form. This is not a compliance claim
by itself; it is an engineering convention chosen because it is stable across Windows and Linux, readable in GitHub
URLs, easy to search, and easy to validate in CI.

| Document type | Location | Naming |
| --- | --- | --- |
| Current index docs | `docs/` | `lower-kebab.md` |
| API docs | `docs/api/` | `lower-kebab.md` |
| Architecture docs | `docs/architecture/` | `lower-kebab.md` |
| Configuration docs | `docs/configuration/` | `lower-kebab.md` |
| Security docs | `docs/security/` | `lower-kebab.md` |
| Observability docs | `docs/observability/` | `lower-kebab.md` |
| ML docs | `docs/ml/` | `lower-kebab.md` |
| Runbooks | `docs/runbooks/` | `lower-kebab.md` |
| Historical FDP docs | Existing folders | `fdp-*` lower-kebab filenames, with historical status in the content |
| OpenAPI specs | `docs/openapi/` | Keep existing service-oriented `*.openapi.yaml` |
| JSON/YAML templates | Domain folder | Preserve machine-readable names when scripts depend on them |

`README.md` is allowed only at repository or external tool boundaries where platform convention expects it.
Documentation under `docs/` uses `index.md` for directory indexes. New docs must not introduce mixed `PascalCase`,
`SCREAMING-CASE`, camelCase, or space-separated names.

## Historical Docs

Historical FDP artifacts keep their branch identifier in the title and body, but filenames use lowercase
`fdp-*` form for repository consistency. If a historical doc is edited, add status/scope clarification rather
than pretending the original risk never existed.

## Audit Rule

Current docs should remain true after merge. Avoid phrases that bind a current document to an active branch or
temporary task. If a document is branch-specific, classify it as historical FDP evidence or a release template.

## Writing Style

- Prefer short paragraphs.
- Keep Markdown lines under 240 characters outside code blocks and URLs.
- Use `Status`, `Scope`, and `Non-claims` sections for large or review-facing docs.
- State limitations directly.
- Do not describe future behavior as current behavior.
- Do not claim production enablement, bank certification, WORM storage, legal notarization, external finality,
  distributed ACID, or exactly-once Kafka unless concrete implemented evidence exists.

## Link Style

- Prefer relative Markdown links.
- Link to current indexes before deep historical files.
- Keep historical links valid after any documentation move.


