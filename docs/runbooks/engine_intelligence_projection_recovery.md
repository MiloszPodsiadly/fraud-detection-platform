# Engine Intelligence Projection Recovery

## Scope

This procedure recovers optional Engine Intelligence projection records handed off by the
`alert-service-engine-intelligence` consumer group to `transactions.dead-letter`. It does not authorize replay of
unrelated baseline failures, mutation of `scored_transactions`, or reconstruction of missing diagnostics.

## Controlled replay

1. Record an approved recovery change, operator identity, source DLT partition and bounded offset range.
2. Select only records whose original consumer-group header identifies the configured Engine Intelligence group.
   Reject records with missing source topic, partition, offset, key, value, or original-group evidence.
3. Confirm that the source event is retained byte-for-byte and that its transaction key is unchanged. Do not inspect,
   log, edit, or export customer payload data during recovery.
4. Copy each approved record once to `transactions.scored` with access-controlled Kafka tooling. Baseline processing is
   occurrence-idempotent; the diagnostic consumer projects only if the event still owns the current scored transaction.
5. Observe bounded consumer lag, `engine_intelligence_projection_*` metrics, and DLT growth. Never put a failed record
   into an automatic replay loop.
6. Verify the current scored occurrence and projection owner agree before closing the recovery record. A stale event
   is a successful no-op; an invalid or conflicting event requires terminal investigation, not identity repair.
7. Retain the original DLT record and recovery evidence until the approved retention policy permits disposition.

Recovery access must be time-bounded and limited to DLT read plus source-topic write. Public API roles, analyst roles,
and ordinary application credentials must not receive these permissions.
