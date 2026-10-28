# Device Risk Evidence Boundaries

Status: design decision. A Mongo observation adapter exists but is not wired to the running pipeline;
Device Risk scoring and public emission are not implemented or enabled.

## Decision

The current transaction fields describe claims supplied by a caller or by synthetic replay. Shape validation and
transport through Kafka do not authenticate a device, customer, institution, detector, or location. No current field is
admissible as verified Device Risk evidence. A future diagnostic may evaluate only facts whose institutional scope,
source authority, event time, and bounded history completeness have been proved at admission. Missing proof produces
`UNAVAILABLE` or `NOT_EVALUATED`, never a zero score, `LOW` risk, or a claim that a device is new or safe. Device Risk
must remain optional and cannot change the existing Rules/ML score, risk level, alert decision, or payment outcome.

This decision does not reinterpret existing rule features. The current `deviceNovelty` is a historical Rules feature,
not a verified physical-device identity or a complete first-seen history.

## Observed path and trust boundary

1. `POST /api/v1/transactions` validates an `IngestTransactionRequest` and passes it to the ingest service
   ([controller](../../transaction-ingest-service/src/main/java/com/frauddetection/ingest/controller/TransactionIngestController.java),
   [request](../../transaction-ingest-service/src/main/java/com/frauddetection/ingest/api/IngestTransactionRequest.java)).
   The ingest module has no production security dependency or authenticated institution/customer binding in this
   path. A gateway or private network may restrict access in a deployment, but that has not been proved as an
   institutional identity source by this repository.
2. The [ingest mapper](../../transaction-ingest-service/src/main/java/com/frauddetection/ingest/mapper/TransactionRequestMapper.java)
   copies request device, location, customer context, and `sourceSystem` into `TransactionRawEvent`. It creates a
   random `eventId` and server `createdAt`; `transactionTimestamp` remains the request value. The
   [synthetic producer](../../transaction-simulator-service/src/main/java/com/frauddetection/simulator/service/SyntheticReplayDataSource.java)
   can also create raw events without the HTTP request validation path. Its scenario flags are demonstration data.
3. The [enriched-event mapper](../../feature-enricher-service/src/main/java/com/frauddetection/enricher/mapper/TransactionEnrichedEventMapper.java)
   carries `deviceInfo`, `locationInfo`, `customerContext`, customer ID, and request transaction time forward, but
   creates a new event ID and does **not** carry `sourceSystem` or an authenticated source identity. The
   [scoring request](../../fraud-scoring-service/src/main/java/com/frauddetection/scoring/domain/FraudScoringRequest.java)
   receives this enriched event and its feature snapshot. Scoring cannot recover lost provenance from a header,
   caller label, or the presence of a value.
4. The [scored-event mapper](../../fraud-scoring-service/src/main/java/com/frauddetection/scoring/mapper/TransactionScoredEventMapper.java)
   carries the same raw `deviceInfo` into the strict scored-event v2 contract. The existing alert detail mapper also
   exposes device fields to its authorized API
   ([mapper](../../alert-service/src/main/java/com/frauddetection/alert/mapper/AlertResponseMapper.java)).
   A Device Risk summary must not add raw identifiers, IP, fingerprint, user agent, or customer-device links to
   diagnostic DTOs, reason text, logs, metrics, or traces.

| Field or group | Observed origin and limitation | Admissibility for Device Risk |
| --- | --- | --- |
| `deviceId`, `fingerprint` | Caller fields in [`DeviceInfoRequest`](../../transaction-ingest-service/src/main/java/com/frauddetection/ingest/api/DeviceInfoRequest.java); syntax/length checks only. Synthetic replay manufactures equivalents. | Unverified tokens. They may identify a repeated *claim* for operational investigation; they cannot prove one physical device or a trusted binding. No active risk signal from them today. |
| `trustedDevice`, `proxyDetected`, `vpnDetected` | Caller booleans in `DeviceInfoRequest`; no provider signature, detector identity, result time, or device binding. | Inadmissible as trust, proxy, or VPN evidence. `true` and `false` are both unverified. |
| `ipAddress`, user agent, platform/browser, arbitrary attributes | Caller values in `DeviceInfoRequest`; IP is not taken from a trusted ingress peer. | Not an attested network origin or stable device identity. No new collection or public exposure for Device Risk. |
| Transaction country, coordinates, `highRiskCountry`, customer home country | Caller values in [`LocationInfoRequest`](../../transaction-ingest-service/src/main/java/com/frauddetection/ingest/api/LocationInfoRequest.java) and [`CustomerContextRequest`](../../transaction-ingest-service/src/main/java/com/frauddetection/ingest/api/CustomerContextRequest.java). | They do not establish device country. The existing `countryMismatch` compares transaction country with claimed home country, not device geography. |
| Top-level `customerId`/`accountId`, nested context IDs, `knownDeviceIds` | Separately supplied in the request and copied by the mapper; no authenticated customer/tenant binding or equality check in this path. | No cross-customer or cross-institution join. A future admission boundary must reject inconsistent identities and derive scope from authenticated context. |
| Institution/tenant | No institution/tenant field or authenticated institutional principal is carried into the raw/enriched/scored event path. | **Absent**. It must be established by an approved authenticated ingress/producer context, then bound immutably to the observation. A request field or `sourceSystem` cannot substitute. |
| `sourceSystem` | Caller string in `IngestTransactionRequest`, copied into raw event, absent from enriched event. | A label, not an issuer attestation; unavailable at scoring. |
| `transactionTimestamp`, event `createdAt` | Caller transaction time is constrained to past/present at HTTP validation; raw/enriched/scored event creation times are set by their producers. Synthetic Kafka production bypasses HTTP validation. | Neither clock proves source truth or complete event-time ordering. Future admission needs bounded skew, authenticated origin, and an immutable as-of cutoff. |

## Candidate signal admission

The following table defines the minimum evidence, not a claim that its producer exists. `UNAVAILABLE` means no fraud
score or negative assertion is emitted for that signal. A complete window must be proved for the same authenticated
institutional scope and scoring occurrence; cold start, eviction, deletion, outage, key rotation, and late arrivals
make completeness unknown until a documented recovery rule proves otherwise.

| Signal | Required facts and source authority | Time and completeness | Current status, confidence, fail-safe, negative proof |
| --- | --- | --- | --- |
| New device | Institution-authenticated customer and independently bound device identity, or a separately approved and explicitly named client-token heuristic. One authoritative pre-event observation history. | Device observation must precede the scoring occurrence in event time; an evidenced bounded window is complete. Never claim first-ever. | **UNAVAILABLE for verified Device Risk.** Client token is spoofable. Test empty/cold/expired history, self-observation, forged `trustedDevice`, duplicate and late event: none may produce `NEW` or `LOW` by default. |
| Same device across customers | Same verified device binding and two distinct authenticated customer identities within one proved institution. | Distinct membership is deduplicated inside a complete bounded window; no cross-institution join. | **UNAVAILABLE.** Equal client strings are not equal physical devices. Test forged IDs, customer mismatch, cross-institution collision, duplicate events and partial history. |
| Device country mismatch | Trusted device-location assertion with provider identity, device binding, observed time and accuracy, plus an approved comparison country. | Both facts must be fresh for the same occurrence and valid under a documented location policy. | **UNAVAILABLE.** Existing transaction/home `countryMismatch` is a different Rules feature. Test injected transaction country, claimed IP/coordinates and stale geo: no device-country result. |
| Device switching | Verified, stable customer/device bindings and distinct event-time ordered transitions from the authoritative history. | Bounded complete window, explicit equal-time tie rule and late-event disposition; replay cannot rewrite a committed snapshot. | **UNAVAILABLE.** Arrival order and changing client IDs are not proof. Test reordered, equal-time, duplicate, late and evicted observations. |
| Device plus VPN/proxy | Verified device binding and a detector-backed network result with provider identity, source IP provenance, confidence, timestamp and scope. | Detector result must be fresh for this occurrence; missing detector or partial history is unknown. | **NOT_EVALUATED.** Caller booleans have no detector provenance. Test forged flags, stale detector result, missing peer IP and provider failure. |

## Activation owners and evidence required

The accountable owners below are organizational roles; this repository does not identify named approvers or record
any of these approvals. Every signal also depends on the shared issuer, occurrence-binding, retention, and delivery
gates. Issuer authentication proves which institution sent a statement; it does not prove that a device or provider
assertion is physically true.

| Signal | Accountable owner(s) | Evidence required to close the gate |
| --- | --- | --- |
| New device | Institution source-integration owner; device identity/enrollment provider owner; platform identity/security owner | Verified issuer and institution/source scope; provider-backed customer/device binding; immutable binding to the scoring occurrence; complete, contiguous pre-event history and approved pseudonym-key lifecycle. |
| Same device across customers | Institution source-integration owner; independent device identity provider owner; data controller/privacy owner | The same independently verifiable device binding for two separately authenticated customers inside one institution; documented authorization, purpose, and retention for cross-customer linkage; negative proof of cross-institution isolation. |
| Device country mismatch | Institution source owner; device-location provider owner; data controller/privacy owner | Provider-authenticated location bound to the device and exact occurrence, with provider identity, observed time, country, and approved freshness/accuracy policy; an independently authorized reference country. A caller IP, transaction country, or home-country field is insufficient. |
| Device switching | Institution event-ledger/source owner; platform recovery/SRE owner; data controller/privacy owner | Authenticated event identity and signed contiguous sequence/coverage seals; approved source-liveness and late-event rules; deterministic equal-time ordering; immutable occurrence snapshots and approved history retention. |
| Device plus VPN/proxy | Trusted ingress/network owner; independent network-intelligence provider owner; device identity provider owner | Trusted provenance for the evaluated network address; provider-authenticated proxy/VPN result and provider code/time bound to the exact occurrence and device binding; freshness and outage behavior. Caller booleans are inadmissible. |

The following shared activation gates remain open. Owners are accountable organizational roles; this ledger records
no named approvers or external approvals:

- **Authenticated issuer and signer/key custody.** Owners: institution source-integration owner; platform
  identity/security and key-management owners. Evidence: independently registered issuer trust roots; signature,
  audience, institution/source scope, expiry, revocation and rotation verification; controlled signer provisioning,
  rotation and revocation.
- **Verified institution/source/epoch seal.** Owners: institution event-ledger/source owner; institution
  source-integration owner; platform identity/security owner. Evidence: a signed, replay-resistant seal bound to the
  verified institution, source and epoch, with contiguous sequence, event-time watermark and coverage interval;
  negative proof for gaps, duplicate seals, replay and cross-scope substitution.
- **Occurrence key and sequence binding.** Owners: institution source-integration owner; platform scoring-contract
  and persistence owners. Evidence: authenticated binding of event, transaction, customer, device/key version,
  source sequence and payload commitment to one immutable scoring occurrence; the reader verifies every binding
  before evidence is consumed.
- **Payload retention, erasure and replay approval.** Owners: data controller/privacy and legal owners; platform
  security/key-custody owner. Evidence: approved purpose, minimization, access, encryption, retention periods,
  erasure disposition, tombstone lifetime and replay horizon, with tests that expiry or erasure cannot recreate live
  history.
- **Durable source closure and recovery worker.** Owners: institution source-liveness owner; platform recovery/SRE,
  operations and audit owners. Evidence: an authorized closure assertion bound to source epoch; durable audit;
  bounded worker lease with owner/token/generation fencing; transactional terminal compare-and-set, one counter
  decrement, restart/race proof, rollback ownership and an operator repair path. No worker or closure path exists now.
- **Exact-payload outbox and Kafka/Redis crash windows.** Owners: feature-enricher owner; Kafka/messaging and Redis
  platform owners; source-offset and consumer owners. Evidence: approved exact serialized payload retention; atomic
  occurrence/outbox intent; stable event identity and identical-byte retries after unknown acknowledgments; fault
  injection across Mongo commit, Kafka publish/ack, source offset, Redis partial write and restart. The current
  Kafka-then-Redis sequence remains at least once and has no cross-system exactly-once guarantee.
- **Consumer compatibility.** Owners: scored-event contract owner and each deployed consumer owner. Evidence:
  inventory of deployed readers, compatibility and payload-size tests, consumer-first rollout and explicit handling of
  the optional nested diagnostic contract.
- **Provider assertions for unsupported signals.** Owners: independent device-identity provider and privacy owners
  for cross-customer linkage; device-location provider and reference-country policy owner for country mismatch;
  trusted-ingress/network and network-intelligence provider owners for proxy/VPN. Evidence: provider-authenticated,
  fresh assertions bound to the exact device and occurrence, with scope, timestamp, accuracy/confidence and outage
  behavior; negative tests for caller and synthetic claims. All three signals remain unsupported.
- **Traffic SLO and measurement.** Owners: platform SRE/service owner and product/model-risk owner. Evidence:
  representative traffic and backlog distributions, p95/p99 latency, throughput, contention/retry and storage
  measurements, explicit acceptance budgets, dashboards and operational response. Local Testcontainers timings are
  not a production SLO; production Device Risk telemetry is not wired.

Activation status remains **ACTIVATION NO-GO** until every applicable shared gate and signal-specific provider gate
has approved evidence. Closing one signal's provider gate does not close these shared gates.

## One authority for each question

- The existing [feature calculator](../../feature-enricher-service/src/main/java/com/frauddetection/enricher/service/TransactionFeatureCalculator.java)
  retains its current Rules semantics: `deviceNovelty` combines the per-customer Redis membership with request
  `knownDeviceIds`; `countryMismatch` compares transaction and home country; proxy/VPN uses caller booleans. The
  [Redis store](../../feature-enricher-service/src/main/java/com/frauddetection/enricher/persistence/RedisFeatureStore.java)
  is an operational feature store with a configured 180-day known-device TTL, not a complete, scoped, immutable
  Device Risk evidence ledger. Its present behavior must not be silently relabelled or changed by this design.
- If authenticated evidence becomes available, one future occurrence-bound ledger will answer only whether a
  *verified* scoped identity was observed in its complete bounded window. It must not import `knownDeviceIds`, use
  Redis `knownDevice` as a fallback, or issue a second answer to the historical Rules question. Storage and delivery
  semantics belong to the next ADR.
- Authenticated institutional identity and approved device/provider evidence must originate outside caller-controlled
  payload fields. No such source is proved in the current pipeline. An absent source blocks admission and scoring
  of the corresponding signal; the existing baseline still operates under its current contract.

## Privacy and pseudonym lifecycle gate

No new history collection or new raw-identifier exposure is authorized by this ADR. Before collection, the data
controller must record purpose, lawful basis, access roles, an exact retention period, deletion/tombstone procedure,
and a subject-rights process. The next storage ADR must set bounded window and TTL, account for incomplete history
after erasure or expiry, and prevent replay from recreating erased live history. Diagnostic reads need backend
authorization and a bounded read audit. Existing raw device fields in transaction/alert records require a separate
retention and access review; this design does not silently broaden their use.

If an approved future source supplies identifiers, the privacy-preserving observation key is a full HMAC-SHA-256
over length-delimited canonical `(authenticatedInstitutionId, identifierKind, identifier)` with a domain such as
`device-risk/device/v1` or `device-risk/customer/v1`. Different domains prevent device/customer equivalence. The
institution scope comes only from authenticated context. A separate, domain-separated verification tag detects an
improbable primary-token collision without retaining the raw identifier; a mismatch is quarantined, never merged.
HMAC is pseudonymization, **not** device attestation, anonymization, or legal certification.

The HMAC secret needs a dedicated, access-controlled configuration, separate from audit-trust and policy-signing
keys and absent from Git, logs, metrics and public DTOs. Production admission fails closed if key, version or scope
is missing. Store the key version with each observation and scoring snapshot. Rotation must not re-hash an already
committed occurrence into a different identity: replay reuses its persisted version and immutable snapshot. If old
key material or a bounded cross-version continuity proof is unavailable, cross-version history is incomplete and
the signal becomes `UNAVAILABLE` until the window is complete again. Authorized erasure removes live membership,
records a replay-resistant tombstone for the applicable retention policy, and marks affected windows incomplete;
handling retained audit evidence requires the approved legal/retention disposition.

These are design gates informed by GDPR data-minimization, storage-limitation and security principles, not a
determination of legal compliance. See [GDPR Articles 5, 25 and 32](https://eur-lex.europa.eu/eli/reg/2016/679/oj)
and the [EDPB pseudonymization summary](https://www.edpb.europa.eu/system/files/2025-02/edpb_summary_202501_pseudonymisation_en.pdf).

## Decision log and executable gates

| Decision | Status and proof required before activation |
| --- | --- |
| Caller device/location/network claims do not become verified evidence through validation, Kafka, or HMAC. | Accepted boundary; negative contract tests must reject forged provenance and raw-data exposure when typed evidence is introduced. |
| No cross-customer or institution-wide Device Risk join without authenticated institutional and customer scope. | Blocked by the current ingress path; negative tests must cover mismatched top-level/context IDs and cross-scope replay. |
| Baseline Rules/ML outputs and historical `deviceNovelty` meaning remain unchanged. | `TransactionFraudScoringServiceEngineIntelligenceEmissionTest.enabledEmissionDoesNotChangeBaseScoringFields` compares scored events with diagnostic emission off/on; `ProducerEngineIntelligenceEnabledDiagnosticDisagreementTest` proves a high diagnostic cannot override a low baseline. Device Risk enabled mode intentionally fails startup, so no event is produced with that mode. Any future activation proposal must repeat baseline parity before the gate changes. |
| New history, event-time policy, retention, key rotation and Kafka/database recovery have one explicit owner. | Deferred to the storage/delivery ADR; cold, late, duplicate, conflict, crash and erasure tests are mandatory before admission. |
| The current nested contract permits the optional Device Risk identity, but does not admit evidence or activate the engine. | The shared registry defines the four-ID allowlist. Runtime registration and emission remain disabled until independently bound evidence, retention, recovery, and deployment handling are approved and verified. |

The executable admission safeguard intentionally only denies: `DisabledDeviceRiskAdmissionAdapter` rejects every raw
event, and startup fails if admission is enabled. Its tagged negative test injects caller-controlled source labels,
device trust/proxy/VPN booleans, synthetic labels, and self-asserted issuer/seal fields; none changes the denial. The
scoring runtime also fails startup when the Device Risk engine flag is enabled, does not register that engine, and the
candidate engine has no evidence reader and always returns `UNAVAILABLE`. These are fail-closed safeguards, not
issuer authentication or proof that an admitted signal works. Pure policy fixtures containing the structural
`AUTHENTICATED_INSTITUTION` enum claim do not establish authenticity. No test claims production admission.
