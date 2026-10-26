# Operations Documentation Index

Status: current operations documentation index.

## Scope

This folder contains operations-oriented branch evidence and the offline policy-publication procedure. Use
[runbooks](../runbooks/index.md) for runtime operator procedures and [observability](../observability/index.md)
for dashboard and metric contracts.

## Offline Procedure

| Document | Scope |
| --- | --- |
| [Local model-promotion policy publication](model_promotion_policy_publication.md) | Separate author, approver and publisher actions, with durable approval evidence. |
| [Model-promotion trust operations](model_promotion_trust_operations.md) | Public-key provisioning, fingerprint checks, rotation, revocation, registry floors and recovery. |

## Branch Evidence

| Document | Scope |
| --- | --- |
| [Regulated mutation rollback plan](regulated_mutation_rollback_plan.md) | Rollback review template and validation checklist for modeled restart/recovery proof. |

Current engine-intelligence operator procedure lives in
[engine intelligence operational runbook](../runbooks/engine_intelligence_operational_runbook.md), not in this
branch-evidence folder.

## Interpretation Rules

- Operations evidence does not approve production enablement.
- Rollback templates require human approval, current generated evidence, and environment-specific change control.
- Operators must not manually edit regulated mutation command documents or business aggregates.
- Current recovery actions are documented in [regulated mutation recovery](../runbooks/regulated_mutation_recovery.md).
