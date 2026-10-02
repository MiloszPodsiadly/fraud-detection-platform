# FDP Branch Evidence

Status: current FDP documentation entry point.

Use this directory for retained branch-level chaos and release evidence. Current repository behavior is interpreted
through the source-of-truth documents linked from `../index.md`.

## Start Here

1. [Branch index](branch_index.md) maps every retained proof family to its claim, key evidence, CI gate, and non-goals.
2. [Evidence status](evidence_status.md) explains how to interpret branch records against the current repository state.
3. [CI evidence map](../ci_evidence_map.md) maps current CI job names to the evidence they protect.
4. [Reviewer checklist](../reviewer_checklist.md) gives the review order for future FDP branches.

## File Groups

| Group | Files | Use for |
| --- | --- | --- |
| Chaos and release readiness | `fdp_36_*` through `fdp_40_*` | Externally coupled chaos, release-governance, and readiness evidence. |

## Branch Evidence Contract

Every retained FDP document is a trace record for one branch or proof family. It should keep its branch id in the
filename, include `Status: branch evidence` or a more specific branch status, and defer current behavior to the
central documentation folders.

## Maintenance Rules

- Keep current architecture and API behavior in `../architecture/`, `../api/`, `../product/`, and `../security/`.
- Keep FDP branch records concise and branch-scoped.
- Link from branch records to central docs instead of duplicating long-lived policy.
- Do not use branch evidence as a production enablement claim unless a current source-of-truth document restates the claim and names the implemented control.
