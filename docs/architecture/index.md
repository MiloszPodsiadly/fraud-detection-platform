# Architecture Documentation

Status: current architecture documentation index.

Use this index to find the current architecture document for review. This directory should describe implemented
system behavior, not branch history. Branch evidence lives in `docs/fdp/branch_index.md`, CI proof lives in
`docs/ci_evidence_map.md`, and current API semantics live in `docs/api/index.md`.

## FDP-54 Architecture Assessment

FDP-54 changes CI, documentation governance, and scope-guard tooling. It does not change service boundaries,
runtime flows, API contracts, RBAC authority semantics, Kafka/event contracts, regulated mutation lifecycle, or
frontend authorization responsibility. The current diagrams remain valid as reviewer aids.

## Reading Order

1. [Current architecture](current_architecture.md) for the system map, invariants, and non-claims.
2. [Architecture diagrams](diagrams.md) for simplified service, mutation, and release-governance flows.
3. [Alert service source of truth](alert_service_source_of_truth.md) for authoritative state stores.
4. [Alert service write path inventory](alert_service_write_path_inventory.md) for mutation/write ownership.
5. [Fraud case management architecture](fraud_case_management_architecture.md) for local lifecycle/idempotency boundaries.

## Document Map

| Document | Status | Use for | Do not use for |
| --- | --- | --- | --- |
| [Current architecture](current_architecture.md) | Current source of truth | System boundaries, invariants, and areas not to change casually | Endpoint-level API details |
| [Architecture diagrams](diagrams.md) | Current reviewer aid | High-level visual orientation and simplified lifecycle/release flows | Complete proof of every state transition |
| [Alert service source of truth](alert_service_source_of_truth.md) | Current source of truth | Authoritative stores and projection boundaries | New persistence contracts without code review |
| [Alert service write path inventory](alert_service_write_path_inventory.md) | Current source of truth | Write ownership, idempotency, recovery, and source-of-truth mapping | Read-only API behavior |
| [Alert service failure windows](alert_service_failure_windows.md) | Current architecture reference | Regulated mutation crash-window review | Production enablement claims |
| [Fraud case management architecture](fraud_case_management_architecture.md) | Current source of truth | Fraud-case local lifecycle, idempotency, transactions, and read boundaries | Regulated mutation finality claims |

## Implementation Detail Documents

These files remain in this directory only because they describe implemented architecture that still exists in the
current system:

| Document set | Current implementation area |
| --- | --- |
| [Evidence-gated finalize compatibility matrix](evidence_gated_finalize_compatibility_matrix.md), [evidence preconditions](evidence_gated_finalize_preconditions.md), [failure windows](evidence_gated_finalize_failure_windows.md), [idempotency replay](evidence_gated_finalize_idempotency_replay.md), [migration rollout](evidence_gated_finalize_rollout.md), [state machine](evidence_gated_finalize_state_machine.md) | Feature-flagged local evidence-precondition-gated submit-decision finalize path. |
| [Regulated mutation safe checkpoint policy](regulated_mutation_safe_checkpoint_policy.md), [safe checkpoints](regulated_mutation_safe_checkpoints.md) | Current safe checkpoint renewal policy for regulated mutation execution. |

## Maintenance Rules

- Do not keep proof-only or readiness-only docs in this directory unless they describe current implementation behavior.
- Do not update diagrams for CI-only or documentation-only changes.
- Update diagrams when a service boundary, source of truth, state transition, or release-governance flow changes.
- Keep frontend authority wording explicit: backend authorization remains authoritative.
- Keep regulated mutation wording conservative: local evidence is not external finality, distributed ACID, or exactly-once Kafka delivery.
