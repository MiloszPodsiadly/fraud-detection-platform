# FDP-34 Safe Checkpoints

The authoritative FDP-34 safe checkpoint policy table is maintained in `docs/architecture/FDP-34-safe-checkpoint-adoption.md`.

This compatibility note exists so maintainers looking for the checkpoint-specific contract can find the table quickly. The invariant is unchanged: Renewal preserves ownership, not progress. No generic heartbeat system and no automatic infinite renewal loop are introduced.
