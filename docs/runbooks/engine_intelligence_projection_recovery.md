# Engine Intelligence Projection Recovery

## Scope

The Engine Intelligence consumer uses a projection-only Mongo inbox when its matching authoritative scoring occurrence
has not been committed yet. Kafka acknowledgement follows successful insertion of the validated envelope; the envelope
contains only occurrence identifiers, the canonical fingerprint, event time, and bounded `EngineIntelligenceSummary`.
It never contains raw customer data, feature vectors, private ML evidence, or arbitrary exception text.
The event time round-trips as canonical timestamp text, epoch second, and nanosecond; missing only part of that
precision metadata is invalid and must be quarantined or repaired from the exact retained source event, never guessed.

The scheduled worker claims due entries with a lease token, retries with bounded delay, and removes an entry only through
the active lease fence after a successful projection or a confirmed stale no-op. Exhausted or over-age entries become
`UNRESOLVED`, remain visible through backlog metrics, and require operator investigation. The dedicated
`engine-intelligence.dead-letter` topic is reserved for EI consumer failures that could not be handed to this inbox; it
must not be mixed with baseline `transactions.dead-letter` recovery.

Live, DLT, and redrive inputs share the strict outer `TransactionScoredEvent` contract: version 2 with exactly one
complete ML evidence outcome. An unsupported outer version or incomplete evidence fails closed before projection;
recovery never selects a historical parser or fabricates missing evidence.

## Controlled projection-only redrive

1. Record an approved recovery change, operator identity, source DLT partition and bounded offset range.
2. Grant a time-bounded recovery identity read access to `engine-intelligence.dead-letter` and write access only to
   `engine-intelligence.redrive`. Enable `ENGINE_INTELLIGENCE_REDRIVE_ENABLED=true` only for the approved window.
   Access to the dedicated redrive topic is the authorization boundary; Kafka headers are consistency evidence and
   never grant recovery permission by themselves.
3. Copy the selected DLT record byte-for-byte, including its original source topic, partition, offset, and consumer
   group headers, to `engine-intelligence.redrive`. Keep the transaction key unchanged. Missing, malformed, or
   inconsistent provenance is sent to `engine-intelligence.quarantine`.
4. Never publish EI recovery records to `transactions.scored`. The redrive listener admits only a bounded recovery
   envelope to `engine_intelligence_pending_projections`; the existing lease-fenced worker invokes only the canonical
   Engine Intelligence projection service. It cannot call baseline alert, fraud-case, suspicious-transaction, outbox,
   ML inference, or fraud-scoring paths.
5. Observe bounded redrive lag, `engine_intelligence_recovery_total`, `engine_intelligence_projection_*`,
   `engine_intelligence_pending_projection_count`, `engine_intelligence_unresolved_projection_count`,
   `engine_intelligence_oldest_pending_projection_age_seconds`, and DLT growth. Never put a failed record into an
   automatic replay loop.
6. Verify the current scored occurrence and projection owner agree before closing recovery. The atomic occurrence
   fence prevents a delayed older projection from replacing a newer accepted projection. A stale event is a successful
   no-op; an invalid or conflicting event requires terminal investigation, not identity repair.
7. Disable the redrive listener and revoke the temporary ACL after the bounded range is consumed. Retain the original
   DLT and quarantine records until the approved retention policy permits disposition.

Public API roles, analyst roles, ordinary application credentials, and the baseline consumer identity must not receive
EI redrive write permission.
