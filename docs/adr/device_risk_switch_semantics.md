# Device Risk Switch Semantics

Status: accepted domain decision; runtime remains disabled pending authenticated admission.

## Context

The stored switch count was derived only from earlier customer observations. That omitted the transition from the
last prior device to the device on the occurrence being evaluated, so an A@10:00 -> B@10:05 change was reported as
zero. A count without an explicit temporal boundary could also be mistaken for a first-ever device or include the
current observation twice.

## Decision

For an occurrence at event time `t`, the complete pre-event history is exactly `[t - 30 days, t)`. The current
occurrence is not inserted into that history before its immutable snapshot is computed. `switches.count` is the
occurrence-inclusive count of device transitions: transitions between chronologically ordered observations in the
pre-event window, plus one transition from the last unambiguously prior device to the current occurrence when their
device identifiers differ.

- A@10:00 -> B@10:05 yields one switch for the B occurrence.
- A@10:00 -> A@10:05 yields zero switches for the A occurrence.
- A -> B -> A at distinct event times yields two switches for the final occurrence.
- A transition between different devices with an equal event time is not ordered by event time; switching is
  `UNAVAILABLE` when that tie could change the transition count.
- A complete window with no prior records yields zero transitions; it means “none observed in this window,” not
  “first-ever.” An incomplete or expired window remains unavailable.
- Commit ordering waits for a staged, relevant predecessor with an earlier event time, or an equal event time and
  lower source sequence. This prevents a later immutable snapshot from omitting a predecessor merely because the
  commit request arrived first. Source sequence is only an ordering input here; it does not authenticate the source.

Replays return the persisted evidence commitment and never recompute a committed occurrence. The history query
excludes the current event, and insertion of its membership and immutable evidence remains in the same Mongo
transaction.

## Consequences

`switches.count` is now explicitly occurrence-inclusive. It is still a descriptive observation, not a calibrated
fraud score or risk level. The complete-history proof, trusted-source requirement, and existing Rules/ML outcomes
remain unchanged. The store and scoring candidate remain inert until a real authenticated issuer, authorized seal,
and occurrence-bound delivery path exist; structural validation and source-sequence ordering do not establish trust.

## Verification

Focused Mongo integration cases cover A->B, A->A, A->B->A, equal-time ambiguity, reverse arrival, and immutable
replay. These adapter tests prove state behavior for supplied inputs, not authenticity of those inputs.
