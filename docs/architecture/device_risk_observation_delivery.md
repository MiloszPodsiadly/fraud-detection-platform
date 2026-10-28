# Device Risk observation and delivery boundary

Status: design decision; a Mongo observation adapter exists but is not wired to the running pipeline.
No trusted producer or Device Risk publication is enabled.
The current A05 delivery decision is an engineering NO-GO; see the [exact-payload delivery gate ADR](../adr/device_risk_exact_payload_delivery_gate.md).
The outbox flow below is a gated design candidate, not an implemented or approved delivery path.
This record depends on [Device Risk evidence boundaries](device_risk_evidence_boundaries.md).
All durations below are proposed policy limits, not permission to collect data. Activation requires an
authenticated institutional source, controller-approved purpose and retention, and the real-store proofs below.

## Decision and current constraints

Use a bounded Mongo observation ledger with an immutable occurrence snapshot and a Mongo outbox for the
optional verified Device Risk path. Mongo is already deployed as a local single-member replica set
([Compose](../../deployment/docker-compose.yml)) and exercised with `MongoTransactionManager` and
Testcontainers in [alert-service tests](../../alert-service/src/test/java/com/frauddetection/alert/messaging/ScoringOccurrenceProcessingMongoIntegrationTest.java).
The feature enricher currently has Redis and Kafka dependencies, no Mongo dependency or configured URI
([POM](../../feature-enricher-service/pom.xml), [configuration](../../feature-enricher-service/src/main/resources/application.yml)); its Compose dependency on Mongo does not create a data store connection.
The local replica set is a test topology, not a production high-availability guarantee.

| Option | Fit and failure mode | Decision |
| --- | --- | --- |
| Reuse `RedisFeatureStore` known-device set | Already fast and configured for 180-day known-device TTL, but keys are customer scoped, values are caller device IDs, membership has no event time/provenance/institution scope, and `recordTransaction` performs separate commands. The separate processed-transaction marker has only an eight-day configured TTL. Expiry/replay cannot prove a complete as-of window. | Keep its historical Rules meaning; reject as verified Device Risk history. |
| New Redis sorted sets/Lua | Could give atomic single-instance commands, but would add retention, fencing, erasure, immutable snapshot and recovery machinery to the operational cache; no proven durable transaction with Kafka. Current deployment uses standalone Redis. | Reject for the authoritative ledger. |
| Bounded Mongo ledger | Existing replica-set transaction and integration-test patterns support unique admission, immutable snapshots, fences and an outbox in one database. Indexed bounded scans and TTL are available; TTL is asynchronous and needs explicit query-time expiry checks. | Select, subject to source and privacy gates. |

The current [enricher](../../feature-enricher-service/src/main/java/com/frauddetection/enricher/service/TransactionFeatureEnricherService.java)
checks a Redis transaction set, reads a live snapshot, constructs a new enriched event ID, publishes Kafka,
then records Redis history. These operations are not atomic; a crash between publish and record can republish
or recompute a different snapshot. The [publisher](../../feature-enricher-service/src/main/java/com/frauddetection/enricher/messaging/KafkaTransactionEnrichedEventPublisher.java)
waits for a broker result, but a lost acknowledgment still leaves publication uncertain. The existing
[consumer error handler](../../feature-enricher-service/src/main/java/com/frauddetection/enricher/config/FeatureEnricherKafkaConfig.java)
has bounded retries and dead-letter handoff, which is not proof of a durable Device Risk occurrence.
This ADR does not claim to repair historical Redis behavior by documentation.

## Identity, schema and indexes

Admission is closed until an authenticated ingress binds `institutionId`, `sourceId`, source event ID,
transaction ID, customer identity, device/provider evidence, source sequence and event time. The current
raw event's random `eventId`, caller `transactionId` and caller `sourceSystem` do not supply that binding
([raw contract](../../common-events/src/main/java/com/frauddetection/common/events/contract/TransactionRawEvent.java),
[mapper](../../transaction-ingest-service/src/main/java/com/frauddetection/ingest/mapper/TransactionRequestMapper.java)).

An observation key is `(institutionId, sourceId, sourceEventId)`. The occurrence key adds the bound
`transactionId` and contract version; transaction ID alone is not unique enough. A canonical, versioned
payload commitment covers the authenticated scope, source ID/sequence/event ID, transaction ID, event time,
typed evidence, pseudonym key version and supported provider assertions. It is a keyed digest over
length-delimited fields, excluding transport headers and processing time. Same key plus same commitment is
a duplicate; same key plus different commitment or a reused transaction ID with incompatible binding is a
terminal conflict. Replaying under a new key version must resolve the original persisted commitment with
the original key version. If it cannot, admission is unresolved, not a new observation.
One candidate is a bounded authenticated evidence bundle for one scoring occurrence; any provider
assertions inside it retain their own provider identity and time. Uncorrelated provider events are not
joined to a transaction by matching caller strings.

Proposed collections (names are local to a dedicated, access-controlled Device Risk database):

| Collection | Minimum persisted fields | Required indexes |
| --- | --- | --- |
| `device_observation_candidates` | Identity, commitment, authenticated source reference, pseudonym tokens and versions, event time, `stagedAt`, admission state, bounded failure code. No raw device ID/IP. | Unique `(institutionId, sourceId, sourceEventId)` and `(institutionId, sourceId, transactionId)`; `(status, stagedAt)` for aggregate pending-age metrics; `(institutionId, state, eventTime, sourceEventId)` for bounded drain. |
| `device_observations` | Same identity, scoped customer/device pseudonyms, typed source quality, event time, accepted sequence, `expireAt`, erasure generation. | Unique observation key; `(institutionId, deviceTokenVersion, deviceToken, eventTime, sourceEventId)` and analogous customer index; TTL on `expireAt` plus query-time `expireAt > now`. |
| `device_occurrences` | Unique occurrence key, input commitment, immutable pre-event fact snapshot, window start/end, source watermark/sequence, coverage version, key versions, policy version, cutoff, stable enriched-event ID and exact serialized output commitment, state. No inferred fraud decision in the observation ledger. | Unique occurrence key; unique `(institutionId, sourceId, sourceEventId)` for one scoring occurrence; state/time index for recovery; retention date index. |
| `device_delivery_outbox` | Deterministic occurrence-linked ID, exact versioned enriched payload or immutable bounded reference, status, attempt count, claim owner/token/generation/expiry, broker result, error code. | Unique occurrence/outbox ID; `(status, nextAttemptAt)` and lease expiry indexes. |
| `device_source_coverage` and `device_scope_fences` | Authenticated source high-watermark, contiguous sequence/coverage interval, policy/key/erasure generation; per institution/customer and institution/device revision and highest committed event-time order. | Unique source and scope keys; compare-and-set on revision. |
| `device_operational_revocation_tombstones` | Scoped pseudonym commitment/version, operational-revocation generation, expiry and replay prohibition, without raw identity. | Unique scope/token/version; retention index. |

Index creation and verification must be a startup gate before admitting data. Unique keys are enforced by
Mongo, not by read-before-write. All collections have explicit size/cardinality limits and access roles;
unknown/malformed records are quarantined with bounded metadata. A TTL delete is eventual, so reads must
filter both event-time window and `expireAt`; TTL alone cannot decide completeness. The observation window
proposal is `W = 30 days`, allowed source lateness `L = 5 minutes`, repair grace `G = 24 hours`, with
`expireAt >= max(acceptedAt, eventTime + W + L + G)`. Occurrence, outbox, idempotency and tombstone
retention must exceed the measured Kafka retention plus redrive/recovery horizon; a provisional 45-day
ceiling is **not** an approved retention policy. If the approved horizon cannot cover replay, old input is
rejected or `UNAVAILABLE`; it is never admitted as fresh history. TTL deletion, cold start, erasure,
unverifiable key rotation or a count/size cap breach revokes completeness for affected windows.
Proposed admission caps are 8 KiB per canonical candidate, 16 KiB per occurrence snapshot, 25,000
observations per customer/window, 10,000 per device/window and 2,000 distinct customers per
device/window, with an institution-level intake budget initially 500 candidates/second. These are
reviewable starting limits for load and privacy approval, not targets to silently truncate to. A cap
breach pauses or quarantines admission and marks dependent signals `UNAVAILABLE`; metrics report
counts and reason codes without identity labels. A production rollout must validate limits against
measured traffic and storage capacity.
An opt-in, local Mongo 7.0 `explain`/latency measurement at the query caps is recorded in the
[Device Risk admission runbook](../runbooks/device_risk_admission.md#opt-in-local-mongo-measurement); it does not
validate production SLOs, contention, or worst-case transaction retries.
The current enriched contract includes raw device fields. Persisting its exact bytes in an outbox would
create another sensitive copy even if the observation ledger is pseudonymized. That copy requires a
separate approved purpose, encryption/access controls, bounded retention and deletion plan. If these
cannot be approved, the integrated outbox path remains off; a hash alone cannot reconstruct an exact
Kafka payload after a crash.

## Event-time admission and pre-event as-of

`known device` means only that a verified, scoped device/customer binding was observed within the
complete half-open window `[t - W, t)`. It never means first-ever known or unknown. A snapshot for
occurrence `O` at event time `t` queries observations with `eventTime < t`; `O` is inserted only after
that snapshot is fixed, in the same Mongo transaction. Equal-time observations do not count one another;
`sourceEventId` breaks ties only for deterministic drain order and pagination. Two different devices
at the same timestamp make a later switching sequence ambiguous, so switching is `UNAVAILABLE` until
the ambiguous interval leaves the bounded window. Repeated events count once
by observation identity, and customer membership counts distinct authenticated customer tokens.

Completeness requires an authenticated source coverage statement that all required source sequences and
event times through `t` have arrived, with no gaps, and coverage continuously spans `[t - W, t)` for
every source needed by that signal. The effective watermark is the minimum of those source cutoffs.
The source seal is accepted only after every preceding sequence has been durably staged; receiving a
watermark message on another Kafka partition or merely observing a high timestamp is insufficient.
Coverage and candidate staging share a Mongo commit or a fenced, gap-checked progression. The source
must define whether a sequence contains zero or one observation and how gaps are closed.
An elapsed processing-time delay alone is not evidence of completeness. A candidate with event time more
than two minutes in the future relative to trusted admission time is quarantined; one arriving within
the proposed five-minute late allowance waits for source sealing. These are proposed bounded controls,
not proof of a source. If no authenticated seal exists, all history-dependent signals are `UNAVAILABLE`.

Candidates are durably staged but do not become queryable observations until their sealed interval is
drained in event-time order. For each affected institution/customer and institution/device scope, a
worker takes a transaction snapshot, checks the next pending candidate and source coverage, reads only
strictly earlier observations, then writes the immutable occurrence, accepted observation, outbox intent
and incremented scope fences in one Mongo transaction. Fence writes and unique indexes serialize
competing workers; a write conflict retries the whole bounded transaction from a fresh snapshot. A
worker may not commit `10:05` while a sealed `10:00` candidate in the same scope is still pending.
If a supposedly complete source later supplies an earlier event, quarantine it, invalidate future
coverage until repaired and raise a source-integrity incident. Do not mutate already committed snapshots.
Late input after a committed cutoff may affect only future evaluations if it falls within their window
and a new trustworthy coverage epoch explicitly admits it; otherwise quarantine. It cannot change an
earlier enriched event or claim that the earlier answer was verified.

If a signal needs device geography or network detection and that provider's coverage is absent, only that
signal is `NOT_EVALUATED`; the other signal windows must still pass their own coverage checks. Missing
history, provider identity, institutional scope, key version or source seal never becomes zero risk.
Persisted candidate states are `STAGED -> COMMITTED` or `STAGED -> QUARANTINED`; duplicate staging
does not create a second candidate. `WAITING_FOR_SEAL` and `WAITING_FOR_EARLIER_EVENT` are retry
outcomes that leave the candidate `STAGED`. Transient infrastructure failure also leaves its prior
state retryable. The durable `pendingCount` equals the number of `STAGED` candidates in that
institution/source scope: staging inserts and increments in one transaction, while commit or
quarantine first compare-and-sets exactly one `STAGED` candidate and decrements the count in that
same transaction. A missing/zero counter is an invariant failure, never a reason to decrement again.
Capacity quarantine also invalidates the source coverage epoch. The occurrence is inserted once as
`COMMITTED` with its immutable snapshot. Unknown transaction commit acknowledgements are retried
boundedly and then reconciled from durable state; unresolved outcomes stay unavailable.

## Delivery state and failure recovery

The future enabled path is `raw Kafka receipt -> authenticated evidence/candidate validation -> durable
candidate staging -> raw Kafka offset completion -> sealed event-time drain -> Mongo transaction
(pre-event snapshot + observation + exact enriched payload and outbox intent) -> outbox Kafka publish
-> Redis baseline record`. A raw offset is acknowledged only after the candidate is durable; replay
before that acknowledgment is handled by its unique key. Waiting for a source seal does not hold a
Kafka listener open. No cross-system transaction is claimed. The Mongo commit makes Device Risk history, occurrence and send
intent locally atomic; Kafka delivery remains at least once. An enriched event uses the one persisted
stable ID and exact payload on every retry. Downstream consumers must deduplicate by that identity.
The existing untrusted/no-evidence path keeps baseline enrichment with no Device Risk claim.

The outbox uses `PENDING -> CLAIMED -> PUBLISHED`, with `FAILED_RETRYABLE`, `FAILED_TERMINAL` and
`CONFIRMATION_UNKNOWN` as explicit states. Claim and completion are fenced by owner, token, generation
and lease expiry. An unknown Kafka acknowledgment retries the **same** payload and ID; a broker send
attempt is not confirmation. A worker cannot publish a freshly recomputed occurrence. A durable
occurrence read resolves an unknown Mongo commit before any alternate baseline publication; read
unavailability keeps the input pending. Invalid evidence/conflicting identity is terminal and must not
be silently dead-lettered as a successful evaluation. Transient Mongo/Kafka errors use bounded retries,
safe error codes, recovery queue age metrics and an operator-visible terminal state.

The existing Redis write is outside the Mongo transaction. In the enabled path its input must be the
persisted occurrence payload, retried idempotently after Kafka confirmation, and completion must be
durably tracked before processing later events for that customer. This order preserves the current
publish-before-record behavior while preventing a later event from reading a partly applied baseline
snapshot. Redis writes require a focused crash/replay proof; an outbox alone cannot provide it. If that
proof or per-customer ordering is absent, **do not enable this integrated path**. An unavailable Device
Risk store cannot be treated as an empty verified history. The option to emit baseline-only output
after a known pre-commit failure is separate from an ambiguous commit and must never create two
different enriched events for one occurrence.
Waiting for an event-time seal can change when the current Redis-based baseline snapshot is read and
therefore may change Rules/ML output for out-of-order arrivals. This ADR has **not** proved baseline
equivalence. If targeted interleaving tests cannot prove it without changing historical semantics,
the optional diagnostic needs a separate later delivery contract; the integrated enriched-event path
remains disabled. No production baseline behavior is changed by this design document.

### Implemented adapter lifecycle limits

The package-private Mongo adapter currently has these concrete lifecycle rules, for adapter tests and
future review only:

- `STAGED` candidates have no TTL and record `stagedAt` for aggregate pending-age inspection via the
  `candidate_status_staged_at` index. Startup rejects a staged candidate without a BSON date `stagedAt`; it never
  backfills the value. Terminal candidates use `terminalExpireAt` and expire 45 days after disposition.
  The adapter accepts only this clean current schema. An old candidate TTL/index, old `expireAt` field,
  `pendingCounterVersion`, an unknown index, or the old `device_erasure_tombstones` collection fails startup with
  `DEVICE_STORE_SCHEMA_INCOMPATIBLE`; startup never drops indexes, rewrites documents, recounts counters, or
  silently clears data.
- Membership history expires at `eventTime + 31 days + 5 minutes`. Occurrence receipts expire 45 days
  after commit. Tombstones expire 45 days and 2 minutes after erasure, covering the accepted two-minute
  future timestamp skew. Reads check expiry themselves because Mongo TTL cleanup is asynchronous.
  Admission rejects events at or beyond the 45-day replay boundary.
- `revokeDeviceFromOperationalHistory` is source scoped: it removes active membership, quarantines staged
  candidates, invalidates that source epoch, and writes a scoped operational-revocation tombstone. It retains
  terminal candidate and committed occurrence/evidence receipts until their receipt expiry. This is **operational
  revocation**, not legal or complete erasure; retained evidence requires an approved legal/retention disposition.
- Identity and history are scoped by `(institution, source, keyVersion)`. A key-version change does not
  infer cross-version device identity. The old source history is not joined to the new version; a complete
  fresh window and trusted continuity protocol would be needed before history-dependent signals could be
  available. Erasure of one source does not erase or aggregate other source scopes.

The adapter exposes a read-only aggregate `pendingMetrics()` query with only total staged count and oldest pending
age; it emits no identity labels and is not wired to a monitoring backend. These rules do not implement an operational
drain/recovery service. There is no scheduled worker to resolve prolonged `STAGED` work, no operator recovery interface,
and no metrics exporter for blocked, timed-out, quarantined, or unresolved states. The 10,000-candidate per-source cap
only bounds stored pending work; it does not guarantee progress. There is no automatic schema migration or counter
reconciliation, and no ongoing repair or restart recovery of unsealed work. The adapter is not production wired, so none of
these proposed retention periods is an authorization to collect data. Approved purpose, retention,
erasure obligations, recovery ownership, and monitoring are activation blockers.

| Interleaving | Durable facts and pre-event snapshot | External emission and recovery |
| --- | --- | --- |
| A at 10:05 arrives before B at 10:00 | Both remain candidates. After authenticated seal, B commits first. A sees B only if B is in its scoped window; neither counts itself. | Each exact persisted payload is sent by its outbox. No early `10:05` availability. |
| Duplicate A, including lost Mongo ACK | Unique key and matching commitment return the staged candidate or, after commit, the existing occurrence/snapshot. | Reuse exact outbox payload if present; never recalculate a committed occurrence or insert membership again. If commit outcome cannot be read, hold. |
| Conflicting A | Unique key with different commitment is terminal; no second observation or snapshot. | No alternative enriched payload; bounded quarantine and alert. |
| Crash after Mongo commit, before publish | Observation, snapshot and outbox intent remain committed together. | Lease/restart publishes the stored payload; source offset may replay safely. |
| Crash after Kafka publish, before broker/source ACK | Same committed occurrence; publish status may be unknown. | Retry exact ID/payload; downstream deduplicates, then repair Redis record and offset. |
| Two workers race on one or linked scopes | One fence/unique-index winner; losing transaction retries and rereads pre-event history. | One occurrence/outbox intent. No last-write-wins or double count. |
| Retention expires or erasure occurs | Affected coverage is incomplete; tombstone blocks replayed resurrection. Existing snapshots remain immutable for their approved lifetime. | Future signal `UNAVAILABLE`; expiry is checked in queries even before TTL cleanup. |
| Device key rotates | Old occurrence retains its version/commitment. Cross-version membership needs proved continuity and authorized key access. | Replay returns old snapshot; missing old key/continuity makes new evaluation unavailable, not a new identity. |

## Activation, rollback and acceptance

1. Add strict typed contracts and an authenticated evidence producer first. Keep Device Risk emission off;
   validate indexes, key custody, retention approval, source watermark/sequence semantics and safe read access.
2. Deploy the Mongo adapter and outbox dark, prove real-store behavior, then replay a bounded authorized
   window into candidates. Do not backfill verified history from raw client device strings or Redis sets.
   Cold start stays `UNAVAILABLE` until the full window is evidenced.
3. Enable per approved institution/source only after current-reader compatibility verification and the baseline Redis
   ordering proof. Compare baseline outputs before/after; turn off admission/emission on breach while
   retaining already committed outbox obligations and immutable occurrences for recovery.
4. Rollback disables new Device Risk observations, not pending exact-payload outbox reconciliation.
   Drain or quarantine obligations under the approved retention policy. Never reinterpret old payloads.

### Manual local/dev schema cutover

The process must never auto-migrate or auto-reset a schema. Only an approved local/dev/test reset may remove the
dedicated Device Risk collections after all application instances using that database are stopped and any required
test evidence is discarded or backed up. The bounded scope is `device_observation_candidates`,
`device_observations`, `device_occurrences`, `device_source_coverage`, `device_scope_fences`,
`device_operational_revocation_tombstones`, and the rejected historical
`device_erasure_tombstones` collection. Never reset alert, governance, model, audit, or unrelated application
collections. In a shared, production, or legally retained environment, `DEVICE_STORE_SCHEMA_INCOMPATIBLE` is an
escalation signal for the data owner, not permission to delete or modify records.

Required focused acceptance tests: unique duplicate/conflict and uncertain commit; pre-event self
exclusion; A/B reverse arrival and equal times; source gap, absent seal, future skew and late violation;
two-worker fence race; cross-institution denial; bounded distinct counts/cap breach; TTL lag/cold
start/erasure/replay; key rotation and missing old key; crashes at every Mongo/Kafka/Redis boundary;
stable event ID/payload on retry; outbox lease takeover and terminal visibility. Use a real Mongo
replica set and Kafka for delivery proofs, plus Redis for the baseline recovery proof. A mock-only
test cannot establish these guarantees. No business risk scoring is authorized by this ADR.

## Typed contract compatibility

`DeviceRiskEvidence` is an internal, versioned, occurrence-bound domain contract in `common-events`.
It does not add fields to raw, enriched or scored events, `featureSnapshot`, the public nested
`EngineIntelligenceSummary` contractVersion 2, API responses, or analyst UI. The five typed fact slots keep
absent/untrusted/invalid states explicit; the existing feature-snapshot normalizer remains the authority for existing
wire features. `device.primary` is already the fourth optional public identity, but that schema readiness does not
admit evidence or activate runtime. A future Device Risk evidence wire change must be consumer-first and separately
versioned. Deserializing an `AUTHENTICATED_INSTITUTION` enum value is
**not** authentication: only a future trusted admission boundary may assign that quality, bind the
expected occurrence and institution, and verify provider assertions before persistence. Until then
the contract is inert and cannot produce a verified Device Risk result.

The current `MongoDeviceObservationStore` foundation stages pseudonymous candidates, seals one
institution/source sequence, commits a scoped pre-event snapshot and observation in one Mongo
transaction, and retains bounded duplicate and operational-revocation receipts. `STAGED` candidates have no TTL;
terminal candidates use a 45-day terminal receipt, history membership uses a 31-day-and-5-minute
event-time horizon, occurrences use a 45-day receipt, and tombstones use 45 days plus the maximum
accepted two-minute future skew. Reads enforce expiry before Mongo TTL cleanup. The clean current schema rejects
old candidate TTL/index state, `pendingCounterVersion`, and old tombstone collection names without modifying them.
These are provisional software horizons, not approved retention periods. Operational revocation removes active
membership and blocks replay but retains candidate/occurrence receipts; it is not legal or complete erasure. No scheduled staged-work drain,
operator recovery service, or lifecycle metrics exist yet. It uses the synchronous Mongo
driver without Spring Mongo auto-configuration, so merely adding the dependency does not connect
the running enricher to Mongo. The adapter is not constructed by production configuration. It caps
uncommitted candidates at 10,000 per source and rejects events older than its provisional 45-day replay
horizon; these limits require production load and retention approval before wiring. Its `CoverageSeal`
is a **structural input**, not a verified source statement: no authenticated producer currently signs
or binds it. One source code is authoritative per scoped history in this foundation; cross-source
completeness and provider assertions are unavailable. No cross-key identity inference exists. Kafka
publication, the exact-payload outbox, runtime key custody, approved retention, drain/recovery ownership
and the baseline Redis ordering proof remain activation gates. Real-store tests prove adapter behavior
for supplied inputs, not authenticity of those inputs.

## Runtime integration gate

Admission is explicitly denied by `DisabledDeviceRiskAdmissionAdapter`. The feature-enricher property
`fraud.device-risk.admission.enabled` defaults to `false`; setting it to `true` fails startup because no trusted
issuer, scoped source authorization, or signed coverage seal is implemented. The adapter does not call the Mongo
store, and no production bean constructs that store. The package-private persistence seam and its structural
`AUTHENTICATED_INSTITUTION` label are not authentication controls.

The running ingest path has no authenticated institution principal or independently bound device
assertion. `deviceId`, `trustedDevice`, proxy/VPN and `sourceSystem` are request fields; `sourceSystem`
is not carried into the enriched event. The simulator creates raw events outside HTTP validation.
Neither a trace header nor a Mongo pseudonym converts these inputs into institutional evidence.
No authenticated source currently issues the contiguous event-time seal required by this ledger.
The adapter therefore has **no production caller or Mongo connection configuration**. On the scoring side,
`ScoringContext` has no independent occurrence authority; `DeviceRiskSignalEngine` has no evidence reader and
always reports `UNAVAILABLE` until a separately authenticated binding can be verified.

### Synthetic replay identity

The simulator and canonical replay generators keep `SYNTHETIC_GENERATOR` or `BASH_JSONL_GENERATOR` source labels
and explicit `deviceEvidenceProvenance=SYNTHETIC_UNTRUSTED` markers. A fingerprint is stable for one synthetic device
ID and is never an admission credential, issuer assertion, signed seal, or institutional provenance.
The simulator property `app.replay.synthetic.deterministic-test-mode` and generator flag
`--deterministic-test-mode` are disabled by default and exist only for fixed-seed/reference-time test snapshots.
They intentionally reproduce event, correlation, transaction, and trace IDs for the same seed, reference instant,
scenario configuration, and dimension data. Deterministic output must not be replayed into a shared or production
topic where those IDs may already exist. Default run mode creates a fresh run identity for every generation.

### Admission activation prerequisites

- [ ] Identify an independently operated institution/source identity provider and validate its issuer, signature,
  audience, scope, expiry, revocation, and key rotation behavior.
- [ ] Bind the authenticated principal to an allowlisted institution and source; ignore request `sourceSystem`,
  customer/device identifiers, and caller-supplied trust flags for authority decisions.
- [ ] Specify how that authority signs and authorizes event sequence, event-time watermark, coverage interval, and
  each provider assertion; reject gaps, replay, cross-scope IDs, and stale or revoked statements.
- [ ] Define the authoritative transaction/customer/device binding and its quality limits independently of issuer
  authentication; a valid issuer does not prove a physical handset.
- [ ] Approve key custody/versioning, privacy erasure, tombstone lifetime, history and evidence retention, and replay
  horizon; verify old occurrences cannot be silently re-linked after rotation.
- [ ] Select and approve durable exact-payload delivery/recovery, consumer rollout, bounded retry/DLT, and unknown
  commit/broker-ack reconciliation before wiring Mongo or publishing Device Risk evidence.
- [ ] Prove with a separately rooted test issuer that production configuration cannot accept synthetic/test identity
  and that Rules/ML baseline decisions remain identical while Device Risk admission is disabled.

The current enricher creates a new enriched event ID on each attempt, publishes it to Kafka, then
performs multiple Redis writes. There is no durable inbox/occurrence payload or outbox for that
baseline operation. Adding a Mongo call or reversing the two calls would leave crash and lost-ACK
windows and could change Rules/ML results. Attaching evidence to the existing feature map would
also require a typed consumer adapter and size policy. The current nested public contract allows four known engine
identities, but that does not admit Device Risk evidence or activate its engine. Consequently, the integrated enabled
path described above is a **design candidate, not an approved runtime path**. Disabled
mode leaves existing enriched events and business decisions unchanged and captures no Device Risk
observations. It makes no verified, zero-risk or low-risk claim.

Before runtime wiring, supply an authenticated institutional/device evidence issuer and a sealed
coverage protocol; approve key custody and retention; verify every deployed reader against the current nested
contract and the documented external-consumer compatibility boundary; then prove an exact-payload inbox/outbox or a
separate diagnostic delivery path against duplicate delivery, commit uncertainty, lost broker ACK and restart. The selected path must keep
mandatory baseline scoring moving during a recoverable device-only outage and prove identical
Rules/ML business effects. If a Mongo commit is ambiguous, reconcile its exact occurrence before
any alternative device publication. Until these conditions hold, production admission remains off.

Mongo transaction, CAS and TTL assumptions follow the [MongoDB atomicity and transactions](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/),
[driver transaction retry](https://www.mongodb.com/docs/manual/core/transactions-in-applications/) and
[TTL timing](https://www.mongodb.com/docs/manual/core/index-ttl/) documentation.
