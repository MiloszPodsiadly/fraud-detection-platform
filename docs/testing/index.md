# Testing Documentation

Status: current testing documentation index.

## Scope

This directory contains branch proof and regression references for regulated mutation safety. These files explain
what CI proves and what it deliberately does not prove. They do not enable production or bank mode.

## Reading Order

1. [Evidence-gated finalize test plan](evidence_gated_finalize_test_plan.md) for the submit-decision finalize model.
2. [FDP-35 regulated mutation readiness proof](fdp_35_regulated_mutation_readiness_proof.md) for modeled recovery.
3. [FDP-36 real chaos proof](fdp_36_real_chaos_proof.md) for real alert-service process restart evidence.
4. [FDP-37 production image chaos proof](fdp_37_production_image_chaos_proof.md) for production-like image restart evidence.
5. [FDP-38 live runtime checkpoint proof](fdp_38_live_runtime_checkpoint_proof.md) for fixture-only live checkpoint kills.

## Document Map

| Document | Use for | Do not use for |
| --- | --- | --- |
| [Evidence-gated finalize test plan](evidence_gated_finalize_test_plan.md) | Required test coverage for the evidence-gated finalize path | Release approval or production enablement |
| [FDP-35 regulated mutation readiness proof](fdp_35_regulated_mutation_readiness_proof.md) | Modeled restart/recovery readiness matrix | Real process-kill proof |
| [FDP-36 real chaos proof](fdp_36_real_chaos_proof.md) | Real alert-service JVM/process restart proof | Production image proof or production certification |
| [FDP-37 production image chaos proof](fdp_37_production_image_chaos_proof.md) | Production-like image durable-state restart proof | Live instruction-boundary production image proof |
| [FDP-38 live runtime checkpoint proof](fdp_38_live_runtime_checkpoint_proof.md) | Dedicated fixture live checkpoint kill proof | Release-image proof or production deployability |

## Maintenance Rules

- Keep one canonical proof document per FDP testing scope.
- Put generated CI output in workflow artifacts, not in repository docs.
- Keep proof-level language exact: modeled proof, real process proof, production-like image proof, and fixture proof are different.
- Do not claim production enablement, bank certification, external finality, distributed ACID, or exactly-once Kafka.
