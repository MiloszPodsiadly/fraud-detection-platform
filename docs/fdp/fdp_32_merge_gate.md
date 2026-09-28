# Regulated Mutation Lease Fencing Gate

Status: current implementation evidence.

FDP-32 is merge-safe as lease-owner fenced command transition hardening. Merge requires:

- claim acquisition remains separate from write fencing
- all post-claim transitions are fenced
- active lease validation occurs before business mutation
- `EvidenceGatedFinalizeExecutor` performs no unfenced command save
- stale and expired workers cannot persist business or evidence progress
- current claim, replay, rollback, recovery, concurrency, and restart tests pass
- transaction-mode `REQUIRED` remains the local business-write safety boundary
- unsupported persisted model versions fail closed
- metrics use bounded labels and no business identifiers

Lease fencing is not production approval, a distributed lock, distributed ACID, external finality, or exactly-once
Kafka delivery.
