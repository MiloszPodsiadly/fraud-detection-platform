# FDP-29 Evidence-Gated Finalize Handoff

FDP-29 should address the remaining design gap that FDP-28 intentionally documents instead of hiding: a business mutation can commit before SUCCESS audit evidence is durably complete.

## Target Guarantee

In regulated mode, no externally visible business mutation should become final unless required audit evidence preconditions are satisfied.

## Candidate Model

1. Accept a command and persist `PENDING_AUDIT`.
2. Anchor ATTEMPTED command evidence.
3. Validate business command and authorization.
4. Prepare SUCCESS evidence path before final visibility.
5. Apply business mutation only after required evidence path is available.
6. Mark command `COMMITTED`.
7. Emit the domain outbox event from the finalized command state.
8. If evidence cannot be prepared, mark `REJECTED_AUDIT_EVIDENCE_UNAVAILABLE` and keep business state unchanged.

## Required Design Decisions

- Where final business visibility is gated: command projection, domain document, or separate finalize table.
- Whether local Mongo transactions are sufficient for command plus domain state.
- How external evidence readiness is proven without claiming distributed ACID.
- How outbox event emission is tied to final command state.
- How operators inspect rejected or pending commands without leaking payloads.

## Out of Scope for FDP-29 Unless Explicitly Approved

- Broker protocol changes
- ML scoring/model changes
- workflow automation
- legal WORM or SIEM integration claims
- cross-database distributed transaction claims

FDP-29 should preserve FDP-28's explicit degradation evidence while reducing the post-commit SUCCESS audit gap through a deliberate finalize design.
