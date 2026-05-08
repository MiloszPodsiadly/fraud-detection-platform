# FDP-29 Design Checklist

This checklist maps FDP-29 design questions to the project documentation that answers them.

| Check | Evidence |
| --- | --- |
| Is visible business commit clearly defined? | `docs/adr/fdp-29-evidence-gated-finalize.md` |
| Are evidence preconditions explicit? | `docs/architecture/fdp-29-evidence-preconditions.md` |
| Is the local ACID boundary clear? | ADR and evidence preconditions |
| Are external/eventual effects clearly outside ACID? | ADR, failure windows, migration rollout |
| Are API statuses unambiguous? | `docs/api/fdp-29-api-response-contract.md` |
| Are old statuses mapped? | `docs/architecture/fdp-29-compatibility-matrix.md` |
| Is idempotency replay defined for every state? | `docs/architecture/fdp-29-idempotency-replay.md` |
| Are failure windows defined? | `docs/architecture/fdp-29-failure-windows.md` |
| Are non-goals explicit? | ADR non-goals section |
| Is the feature-flagged runtime scope clear? | Handoff, migration rollout, and API response contract |

## Documentation Scope

This checklist documents the FDP-29 contract and review questions, but it does not make a merge decision.
