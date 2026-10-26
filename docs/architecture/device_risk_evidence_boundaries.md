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
| Baseline Rules/ML outputs and historical `deviceNovelty` meaning remain unchanged. | Required regression: identical score, risk, alert and reason output with Device Risk disabled and enabled; future diagnostic failure must not rewrite baseline facts. |
| New history, event-time policy, retention, key rotation and Kafka/database recovery have one explicit owner. | Deferred to the storage/delivery ADR; cold, late, duplicate, conflict, crash and erasure tests are mandatory before admission. |
| No fourth public engine in Engine Intelligence v1. | Existing [identity contract](../../common-events/src/main/java/com/frauddetection/common/events/engine/FraudEngineIdentityContract.java) and [summary](../../common-events/src/main/java/com/frauddetection/common/events/intelligence/EngineIntelligenceSummary.java) allow three. Public emission stays off until a versioned consumer-first migration is proved. |

No new executable trust policy exists yet, so adding a test that claims an admitted Device Risk signal here would
assert future behavior. The existing negative public-contract tests reject unknown engine identities; typed evidence
admission, scope and source-poisoning negatives belong with the later contract and persistence implementation.
