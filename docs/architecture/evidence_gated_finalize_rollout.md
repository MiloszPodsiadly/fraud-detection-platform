# Evidence-Gated Finalize Runtime

Status: current-only regulated mutation model.

`EVIDENCE_GATED_FINALIZE_V1` is the only executable regulated mutation model. Runtime routing does not contain a
fallback model, and no configuration flag can select a retired executor.

## Startup Contract

The runtime requires Mongo transaction capability, the transactional outbox repository and recovery path, registered
mutation recovery strategies, bounded local audit writing, required local audit-chain indexes, and a persisted-model
preflight with no unsupported records. Startup fails closed when a requirement is missing.

Missing, null, retired, and unknown persisted contracts are unsupported. The read-only preflight checks raw model,
revision, state, and execution-status values before domain mapping. Every unsupported document in the active command
collection blocks startup, including terminal records. Operators must archive or migrate those records offline under
an approved data-handling procedure before restart. The application does not reinterpret, rewrite, purge, or silently
migrate them.

## Current State Flow

Commands progress through `REQUESTED`, `EVIDENCE_PREPARING`, `EVIDENCE_PREPARED`, `FINALIZING`, and
`FINALIZED_EVIDENCE_PENDING_EXTERNAL`. Evidence confirmation may promote a finalized command to
`FINALIZED_EVIDENCE_CONFIRMED`. Rejection, validation failure, and recovery-required states remain explicit.

Recovery state wins over a response snapshot. A stale or ambiguous finalize must not be reported as success. Replay
with the same canonical intent must not repeat business mutation, local success audit, or transactional outbox write.

## Operational Boundary

Rollback means deploying compatible current runtime code and configuration; it never means downgrading stored commands
to removed semantics. External witnesses, trust-authority signatures, and Kafka confirmation remain outside the local
Mongo transaction. This runtime does not provide distributed ACID, exactly-once delivery, WORM storage, or legal
notarization.
