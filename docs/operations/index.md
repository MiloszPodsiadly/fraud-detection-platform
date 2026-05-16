# Operations Documentation Index

Status: current operations documentation index.

## Scope

This folder contains operations-oriented branch evidence that is not a live runbook. Use [runbooks](../runbooks/index.md)
for current operator procedures and [observability](../observability/index.md) for dashboard and metric contracts.

## Branch Evidence

| Document | Scope |
| --- | --- |
| [FDP-35 regulated mutation rollback plan](fdp_35_regulated_mutation_rollback_plan.md) | Rollback review template and validation checklist for FDP-35 modeled restart/recovery proof. |

## Interpretation Rules

- Operations evidence does not approve production enablement.
- Rollback templates require human approval, current generated evidence, and environment-specific change control.
- Operators must not manually edit regulated mutation command documents or business aggregates.
- Current recovery actions are documented in [regulated mutation recovery](../runbooks/regulated_mutation_recovery.md).
