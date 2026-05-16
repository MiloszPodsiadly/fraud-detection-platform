# FDP Branch Evidence

Status: current FDP documentation entry point.

Use this directory for branch-level FDP records: merge gates, branch summaries, handoff notes, readiness proof,
and enablement checklists. These files are evidence for the branch that produced them. Current repository behavior
is interpreted through the source-of-truth documents linked from `../index.md`.

## Start Here

1. [Branch index](branch_index.md) maps every retained FDP branch to its claim, key evidence, CI gate, and non-goals.
2. [Evidence status](evidence_status.md) explains how to interpret branch records against the current repository state.
3. [CI evidence map](../ci_evidence_map.md) maps current CI job names to the evidence they protect.
4. [Reviewer checklist](../reviewer_checklist.md) gives the review order for future FDP branches.

## File Groups

| Group | Files | Use for |
| --- | --- | --- |
| Regulated mutation foundation | `fdp_25_*` through `fdp_34_*` | Local mutation, replay, lease, and checkpoint evidence. |
| Chaos and release readiness | `fdp_35_*` through `fdp_40_*` | Recovery, chaos, release-governance, and readiness evidence. |
| Fraud case product work | `fdp_42_*` through `fdp_47_*` | Fraud-case lifecycle, read model, and analyst console product evidence. |
| Frontend runtime architecture | `fdp_48_*` through `fdp_53_*` | BFF, route boundary, workspace runtime, and UI architecture evidence. |

## Branch Evidence Contract

Every retained FDP document is a trace record for one branch or proof family. It should keep its branch id in the
filename, include `Status: branch evidence` or a more specific branch status, and defer current behavior to the
central documentation folders.

## Maintenance Rules

- Keep current architecture and API behavior in `../architecture/`, `../api/`, `../product/`, and `../security/`.
- Keep FDP branch records concise and branch-scoped.
- Link from branch records to central docs instead of duplicating long-lived policy.
- Do not use branch evidence as a production enablement claim unless a current source-of-truth document restates the claim and names the implemented control.
