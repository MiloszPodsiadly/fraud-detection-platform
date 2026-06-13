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
| [Engine intelligence operational hardening](engine_intelligence_operational_hardening.md) | FDP-100 operational metrics, retention, and index strategy | Low-cardinality operational metrics, retention/audit relationship, and existing index strategy for projection, feedback submit, and feedback read | Claiming new API/UI surfaces, dashboards, global feedback search, case aggregation, feedback export, retraining, rule updates, decisioning, or automatic TTL deletion |
| [Engine intelligence feedback read model](engine_intelligence_feedback_read_model.md) | FDP-99 bounded transaction-scoped feedback read model | Governance/review-only feedback reads, explicit feedback-read authorization, bounded first-page semantics, submittedBy privacy, and no decisioning/retraining/rule-update boundaries | Claiming analytics dashboards, global search, case aggregation, cursor continuation, training export, model retraining, rule updates, approve/decline/block, alert severity changes, or fraud case status changes |
| [Engine intelligence API read model](engine_intelligence_api_read_model.md) | FDP-96 bounded transaction-scoped read API | Dedicated read-only DTO boundary, authorization ordering, missing-projection semantics, and no-leakage rules | Claiming UI exposure, list/search API, case-level aggregation, scoring invocation, or decisioning |
| [Engine intelligence alert-service projection](engine_intelligence_alert_service_projection.md) | FDP-95 internal alert-service read model | Bounded Mongo projection, safe omission, replay safety, no-raw storage, and internal-only boundary | Claiming API/UI exposure, alert decisioning, fraud-case status changes, or payment authorization changes |
| [Controlled engine intelligence producer emission rollout](engine_intelligence_producer_emission_rollout.md) | FDP-94 disabled-by-default runtime producer emission | Specific emission flag, optional diagnostic enrichment, failure isolation, rollback, and baseline-scoring boundary | Claiming baseline scoring migration, default production emission, alert-service projection, persistence, API/UI exposure, or final decisioning |
| [Consumer-first engine intelligence rollout readiness](engine_intelligence_consumer_readiness.md) | FDP-93 consumer-readiness foundation | Known `TransactionScoredEvent` consumers, shared compatibility fixtures, alert-service tolerance, and future producer rollout gate | Claiming producer emission, alert-service projection, persistence, API/UI exposure, or final decisioning |
| [Public engine intelligence event contract](public_engine_intelligence_event_contract.md) | FDP-92 contract foundation with FDP-94 optional producer wiring | Optional bounded public engine-intelligence DTOs, compatibility rules, field allowlist, and controlled producer mapping | Claiming default emission, alert-service projection, API/UI consumption, or final decisioning |
| [Internal engine result aggregation and comparison](internal_engine_result_aggregation.md) | FDP-91 internal-only comparison foundation | Bounded normalized engine results, internal agreement, mismatch, score-delta, strongest-signal, and leakage-prevention semantics | Claiming event/API/UI exposure, production scoring wiring, public `engineResults[]`, or final decisioning |
| [Multi-engine scoring contract boundary](multi_engine_scoring_architecture.md) | FDP-101 bounded contract maintenance | Shared `FraudEngineResult` bounds, status semantics, sample payload policy, and no Kafka/API/UI/export integration boundary | Claiming final decisioning, payment authorization, automatic approve/decline/block, feedback dataset export, or new runtime integration |
| [Shadow Performance Summary generation job](shadow_performance_summary_generation_job.md) | FDP-109 manual/local/offline generation foundation | Explicit local current-summary generation from FDP-102/FDP-103/FDP-104/FDP-105 chain, atomic publish, validation, and no fallback semantics | Claiming production scheduler, promotion readiness, threshold recommendation, decisioning, payment authorization, analyst recommendation, Kafka triggers, model registry mutation, or online scoring changes |
| [Shadow Performance generated runtime override](shadow_performance_generated_runtime_override.md) | FDP-110 local generated-artifact runtime bridge | Explicit local compose override that mounts FDP-109 generated current-summary.json into the FDP-108 provider path | Claiming runtime generation, demo fallback, production scheduler, promotion readiness, threshold recommendation, decisioning, payment authorization, analyst recommendation, Kafka triggers, model registry mutation, or online scoring changes |
| [Orchestrator runtime readiness foundation](orchestrator_runtime_readiness.md) | FDP-90 internal runtime-hardening foundation only | Per-engine deadline policy, bounded executor policy, cooperative cancellation semantics, latency measurement, low-cardinality metrics abstraction, and runtime isolation | Claiming production scoring migration, forced JVM work termination, Kafka event/API/UI/projection integration, final decisioning, or public `engineResults[]` |
| [Internal fraud scoring orchestrator](fraud_scoring_orchestrator.md) | FDP-89 foundation with FDP-90 hardening and FDP-94 opt-in diagnostic wiring | Internal `FraudScoringOrchestrator`, deterministic adapter execution, bounded per-engine failure containment, deadline enforcement, and diagnostic runtime boundary | Claiming baseline scoring migration, API/UI/projection integration, final decisioning, or public `engineResults[]` |
| [Python ML signal engine adapter](python_ml_signal_engine_adapter.md) | FDP-88 adapter foundation only | Internal `PythonMlSignalEngine` adapter contract, existing ML source-of-truth delegation, unavailable semantics, bounded ML evidence, and runtime isolation | Claiming production scoring migration, orchestration, event/API/UI/projection integration, final decisioning, or `engineResults[]` |
| [Rule-based signal engine adapter](rule_based_signal_engine_adapter.md) | FDP-87 adapter foundation only | Internal `RuleBasedSignalEngine` adapter contract, safe feature consumption, bounded evidence, and runtime isolation | Claiming production scoring migration, orchestration, event/API/UI/projection integration, or `engineResults[]` |
| [Feature snapshot consumption policy](feature_snapshot_consumption_policy.md) | FDP-85 internal adapter-consumption policy only | Typed, allowlisted reads of internal `ScoringContext.featureSnapshot` for future adapters | Claiming adapters, runtime wiring, event, API, UI, or decisioning integration |
| [Fraud signal engine boundary](fraud_signal_engine_boundary.md) | FDP-84 internal interface foundation only | Internal engine interface and static descriptor boundary | Claiming runtime orchestration, event, API, UI, or decisioning integration |
| [Scoring context boundary](scoring_context_boundary.md) | FDP-83 internal foundation only | Internal scoring input model boundary | Claiming runtime, event, API, UI, or projection integration |
| [Multi-engine scoring contract boundary](multi_engine_scoring_architecture.md) | Contract foundation only | FDP-82 shared engine-result boundary | Claiming runtime orchestration or event integration |
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
