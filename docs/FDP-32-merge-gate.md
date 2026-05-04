# FDP-32 Merge Gate

FDP-32 is merge-ready when the regulated mutation lease-fencing invariant is preserved:

- all post-claim command transitions go through `RegulatedMutationFencedCommandWriter`
- executors do not use blind repository saves for claimed transitions
- stale worker transition is rejected after lease takeover
- current lease owner transition succeeds
- legacy stale worker cannot mark `SUCCESS_AUDIT_PENDING`
- FDP-29 stale worker cannot mark `FINALIZED_EVIDENCE_PENDING_EXTERNAL`
- recovery states are not overwritten by stale workers
- business mutation is not rerun after stale rejection
- metrics use bounded labels only
- FDP-31 claim and replay tests remain green
- FDP-29 coordinator integration tests remain green
- feature flags remain unchanged
- public API statuses remain unchanged

Production enablement remains separate from this hardening branch.
