# Regulated Mutation Safe Checkpoints

Checkpoint renewal must not be treated as business progress.

The authoritative safe checkpoint policy table is maintained in `docs/architecture/regulated_mutation_safe_checkpoint_policy.md`.

This compatibility note exists so maintainers looking for the checkpoint-specific contract can find the table quickly.
The invariant is unchanged: Renewal preserves ownership, not progress. Checkpoint renewal preserves bounded lease
ownership. It does not prove business progress. No generic heartbeat system and no automatic infinite renewal loop are
introduced.

Production executors require the Spring-managed checkpoint renewal service. `disabled()` is only for compatibility and
unit-test constructors. Checkpoint renewal failure stops execution and must not be classified as post-commit audit
degradation.

A successful checkpoint renewal is not proof that `ATTEMPTED` audit completed, business mutation completed, outbox was
written, success audit was recorded, evidence was prepared, local finalize completed, external confirmation completed,
Kafka delivered, or legal/auditor finality was reached.
