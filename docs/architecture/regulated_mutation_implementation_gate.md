# Regulated Mutation Implementation Gate

Status: current implementation evidence.

Merge requires:

- only `EVIDENCE_GATED_FINALIZE_V1` is executable
- missing, null, retired, and unknown persisted contracts fail closed, including terminal records in the active
  command collection
- the read-only persisted-model preflight reports unsupported records without mutation
- unsupported records are archived or migrated offline before startup; runtime startup never rewrites or deletes them
- canonical intent conflicts are rejected before business mutation
- lease ownership and every claimed transition are fenced
- checkpoint renewal failure stops execution
- local finalize atomically persists business state, outbox, audit evidence, snapshot, and marker
- rollback tests prove no partial local finalize
- recovery never projects stale snapshots as success or repeats business mutation
- restart and chaos proofs preserve idempotency and evidence integrity
- current public statuses and OpenAPI are aligned
- required CI selectors execute real tests and cannot pass with zero tests

The gate does not claim distributed ACID, exactly-once Kafka delivery, external finality, WORM storage, legal
notarization, or production approval.
