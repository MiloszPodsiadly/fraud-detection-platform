# FDP-29 Design Checklist

Status: branch evidence.


This checklist maps FDP-29 design questions to the project documentation that answers them.

| Check | Evidence |
| --- | --- |
| Is visible business commit clearly defined? | `docs/adr/fdp_29_evidence_gated_finalize.md` |
| Are evidence preconditions explicit? | `docs/architecture/evidence_gated_finalize_preconditions.md` |
| Is the local ACID boundary clear? | ADR and evidence preconditions |
| Are external/eventual effects clearly outside ACID? | ADR, failure windows, migration rollout |
| Are API statuses unambiguous? | `docs/api/evidence_gated_finalize_response_contract.md` |
| Are old statuses mapped? | `docs/architecture/evidence_gated_finalize_compatibility_matrix.md` |
| Is idempotency replay defined for every state? | `docs/architecture/evidence_gated_finalize_idempotency_replay.md` |
| Are failure windows defined? | `docs/architecture/evidence_gated_finalize_failure_windows.md` |
| Are non-goals explicit? | ADR non-goals section |
| Is the feature-flagged runtime scope clear? | Handoff, migration rollout, and API response contract |

## Documentation Scope

This checklist documents the FDP-29 contract and review questions, but it does not make a merge decision.
