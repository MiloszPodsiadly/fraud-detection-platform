# Retained FDP Evidence Status

Status: current evidence interpretation index.

## Scope

This directory retains only chaos and release evidence whose identifiers are consumed by active CI jobs, artifact
verification, deployment fixtures, or external release controls. It is not the source of truth for current runtime
architecture.

For current behavior, start with `docs/index.md`, `docs/architecture/index.md`, `docs/api/index.md`, and
`docs/ci_evidence_map.md`.

## Current Status

| Evidence family | Still valid | Explicit boundary |
| --- | --- | --- |
| FDP-36 | Real alert-service process kill and restart evidence | Does not certify production enablement |
| FDP-37 | Production-like image durable-state chaos evidence | Does not prove every live instruction boundary |
| FDP-38 | Test-fixture live checkpoint evidence | Fixture evidence is not release-image evidence |
| FDP-39 | Release artifact separation and governance evidence | Does not authorize production deployment |
| FDP-40 | Release-control readiness and named external gaps | Does not prove external platform enforcement |

## Interpretation Rule

Use `branch_index.md` to locate retained evidence and `docs/ci_evidence_map.md` to identify the gate that validates
it. If retained evidence conflicts with current architecture or API documentation, the current domain document is
authoritative.

Renaming FDP-36 through FDP-40 jobs, artifact keys, scripts, or proof files requires a coordinated migration of CI
required checks and external release consumers. Their ticket-derived names are retained as evidence protocol names,
not as runtime architecture names.
