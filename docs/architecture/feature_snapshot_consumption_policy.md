# Feature Snapshot Consumption Policy

Status: current feature-snapshot consumption policy with historical FDP-85/FDP-129 notes retained only as background.

## Historical FDP-85 Scope

FDP-85 introduced the internal typed-reader policy for `ScoringContext.featureSnapshot`. At that historical point it
did not add runtime adapters, orchestrator wiring, public Engine Intelligence event emission, alert-service
projection, API exposure, or Analyst Console rendering. Those FDP-85 non-goals remain useful history only; they are
not a description of the current branch.

## Current Runtime Architecture

The current branch has a diagnostic multi-engine runtime. Rules, ML, and optional Velocity execute through
the current orchestrator path and produce bounded internal engine results that are aggregated into public
`TransactionScoredEvent.engineIntelligence` when diagnostic emission is enabled. Alert-service can project that
public summary, expose bounded read DTOs/OpenAPI, and the Analyst Console can render those bounded diagnostics.

`featureSnapshot` is transported inside controlled internal Kafka events such as enriched and scored transaction
events. It remains an internal fact payload, not the public analyst/API Engine Intelligence payload. Engine
Intelligence exposes bounded summaries, buckets, identities, reason codes, diagnostic signals, and warning counts,
not raw snapshot data.

## Wire Boundary And Normalization

`common-events` owns the event wire boundary for feature-snapshot values through `FraudFeatureContract`,
`FeatureSnapshotWireValueNormalizer`, and `FeatureSnapshotWireValueDeserializer`. Decimal wire values such as
`recentAmountSumPln` and `currentTransactionAmountPln` must round-trip as `BigDecimal`; integer, double, boolean, and
string facts must round-trip as their declared scalar types.

`transactions.enriched` uses the shared Jackson JSON serializer/deserializer in `common-events`; there is no Schema
Registry, Avro, or Protobuf contract in the current implementation. The `TransactionEnrichedEvent` JSON contract is
the event envelope plus `featureSnapshot`. Fraud fact fields such as `recentTransactionCount`,
`recentAmountSumPln`, `transactionVelocityPerMinute`, `merchantFrequency7d`, `deviceNovelty`, `countryMismatch`, and
`proxyOrVpnDetected` must not be dual-written as top-level event fields.

The hard cutover line is the canonical snapshot consumer cutover boundary: deployed consumers must already read
canonical facts from `featureSnapshot` before the current producer shape is used. Current consumers ignore unknown
duplicate top-level fields when reading older enriched-event JSON during replay, but current producers no longer write
those duplicates and no active consumer may depend on them. Retry and dead-letter handling preserves the same
canonical payload shape; invalid current canonical facts are dead-lettered rather than repaired from removed
duplicates.

The rollout order is consumer-first:

1. all independently deployed consumers become `featureSnapshot`-ready;
2. deploy those consumers;
3. verify no active dependency on removed top-level fraud facts remains;
4. deploy the producer that writes the snapshot-only current shape;
5. observe retry, DLT, and replay paths;
6. wait the agreed Kafka retention period or migrate retained durable data where necessary;
7. delete temporary replay compatibility after the explicit cutoff.

Services that consume event payloads must not repair malformed current canonical facts by coercing strings, booleans,
nested objects, or oversized numeric input into acceptable scalar values. Missing old data is compatibility; present
invalid canonical data is corruption.

## Adapter Consumption Policy

`FraudSignalEngine` adapters consume the normalized snapshot through `ScoringContext` and `FeatureSnapshotReader`,
not by reparsing JSON, casting raw `Map<String, Object>` values, or calling
`context.featureSnapshot().get(...)` directly in adapter logic.

Adapter consumption is not key-only. Canonical feature keys are declared in `FraudFeatureContract` and remain
camelCase wire names.
`FeatureSnapshotKeyPolicy` permits only registered safe keys from `FraudFeatureContract`. A registered key is not
automatically adapter-consumable: consumption requires both the key and expected scalar type to match policy, through
an allowed key and the matching scalar accessor.
`isAllowedFeatureKey` is not adapter-consumption permission. It means the key is known and safe enough for policy
evaluation; adapters must still use `FeatureSnapshotReader` or `expectedTypeFor`.
Java adapter keys are the Java-enriched `featureSnapshot` transport keys only. Python-derived model vector features
such as `suspiciousFactRatio` and `rapidTransferBurst` remain ML model features and must not become Java snapshot
adapter keys just because the ML model contract names them.

Examples:

- `deviceNovelty` is boolean.
- `recentTransactionCount` is integer.
- `transactionVelocityPerMinute` is double.
- `currency` is string.
- `rapidTransferTransactionIds` is not consumable by current Rules scoring.
- Unsupported policy marker fields are not consumable by current Rules scoring.

Wrong accessor use is not valid consumption. `stringValue("deviceNovelty")` and
`booleanValue("currency")` must fail with bounded status rather than silently coercing data.

## Canonical-Versus-Legacy Precedence

Current canonical feature-snapshot values are authoritative for new scoring. When a canonical field required by an
adapter is present and valid, the adapter uses it. When that canonical field is present but invalid, wrong-typed, out
of domain, nested, or contradictory, the adapter must fail closed. It must not activate a scoring fallback and must
not publish a fake low-risk or zero-score result.

Historical replay compatibility belongs to explicit event/read compatibility boundaries. It must not be implemented
as a current Rules scoring fallback from removed feature-flag, case-candidate, or top-level duplicate facts.

## Invalid, Present, And Missing Semantics

- `PRESENT` means the key exists and its value exactly matches the requested scalar accessor type.
- `MISSING` means the key is absent.
- `INVALID_TYPE` means the key exists and the accessor matches policy, but the runtime value type does not match the
  expected Java class.
- `WRONG_ACCESSOR` means the key is scalar-consumable, but the caller used an accessor that does not match the
  policy-declared scalar type.
- `NOT_ALLOWED` means the key is forbidden or outside policy.

A missing boolean is not false. A missing number is not zero. A missing string is not an empty string. An invalid
type is not coerced: string `"true"` is not boolean `true`, string `"3"` is not integer `3`, and integer `1` is not
boolean `true`. `NOT_ALLOWED` and exception messages must not expose raw rejected keys.

Top-level null keys are invalid. Top-level null values are invalid. Unknown or unavailable values must not be
represented by null. Arbitrary nested structures are not consumed by the scalar reader. Nested `Map` or `List`
values are not scalar facts and scalar accessors return `INVALID_TYPE` for them.

## Evidence And Privacy Restrictions

Feature snapshots may contain useful internal facts, including scalar string values such as `customerSegment` or
`merchantCategory`. Reading a string feature internally does not authorize exposing the raw value in
`FraudEngineResult` evidence, Engine Intelligence, analyst explanations, logs, metrics, or UI.

Engine outputs and public read DTOs must use bounded labels, reason codes, score buckets, warning codes, and safe
diagnostic signal identifiers. They must not expose raw payloads, raw feature vectors, raw snapshot dumps, request or
response bodies, headers, authorization data, tokens, secrets, passwords, stack traces, exception text, PAN/card or
account identifiers, SSNs, email, phone, raw device fingerprints, raw user-agent values, hostnames, endpoints, URLs,
training labels, ground truth, payment authorization instructions, or final decisioning instructions.

## Velocity PT1M Policy

The optional `velocity.primary` diagnostic adapter reads only typed factual scalar features
through `FeatureSnapshotReader`: `recentTransactionCount`, `recentTransactionCountWindow`, `recentAmountSumPln`, and
`transactionVelocityPerMinute`.

Velocity v1 is the current independent Velocity diagnostic contract, not a Rules V1 compatibility path. It requires
`recentTransactionCountWindow=PT1M`; producer meaning, consumer validation, and policy meaning
all use that one-minute observation window. Changing the window requires a versioned contract and policy update.
Velocity validates count/window/rate consistency and degrades on impossible present values instead of silently
choosing one fact. Velocity remains optional, diagnostic-only, and not a calibrated probability, final decision,
payment authorization, case action, threshold recommendation, or analyst recommendation source.

Velocity v1 consumes current canonical count/window/rate/amount facts only; rapid-transfer threshold semantics are
owned by `FraudFeatureThresholdContract`.

## Rules Canonical Input Policy

Rules V2 uses canonical feature-snapshot facts only. Required input is:
`recentTransactionCount`, `recentTransactionCountWindow`, `transactionVelocityPerMinute`,
`recentAmountSumPln`, `recentAmountSumWindow`, `currentTransactionAmountPln`, `merchantFrequency7d`,
`deviceNovelty`, `countryMismatch`, `proxyOrVpnDetected`, and `currency`.

Present-invalid canonical Rules inputs fail closed: string counts, string decimals, negative counts, negative
amounts, invalid or missing required canonical time windows, nested values, booleans used as numbers, and oversized
numeric input must not become `AVAILABLE LOW` and must not fall back to removed feature-flag or case-candidate
representations.

Rules V2 time-dependent canonical facts use the explicit `PT1M` window. `recentTransactionCount` requires
`recentTransactionCountWindow=PT1M`; `recentAmountSumPln` requires `recentAmountSumWindow=PT1M`. Present-invalid
window data is canonical corruption, not compatibility.

Valid canonical facts are authoritative. Predicate false is not missing, and PLN thresholds consume canonical PLN
facts only.

Supported monetary currencies for ingest and enrichment are `PLN`, `EUR`, `USD`, and `GBP`. Unsupported or null
currencies are rejected at the boundary and fail closed in enrichment. Unknown currencies are never converted as PLN
and are never allowed to produce Rules PLN threshold facts.

Primary scoring and diagnostic Engine Intelligence have different failure semantics. The primary
`RuleBasedFraudScoringEngine` fails closed with a bounded validation exception and no fabricated scored event.
The diagnostic `RuleBasedSignalEngine` reports bounded `DEGRADED` with null score/risk, while the orchestrator may
continue eligible diagnostic engines. Raw invalid values must not appear in public output, logs, metrics, or
diagnostic evidence.

## Retained Compatibility

This branch deliberately retains compatibility that is still needed for durability, replay, and rolling deployments:

- `EngineIntelligenceComparisonV1Compatibility` for historical comparison objects read through a
  `TransactionScoredEvent` whose outer event proves `modelVersion=v1` and whose comparison contains the complete
  legacy semantic triplet.
- Old-event `engineIntelligence == null` handling so historical Kafka events remain readable as explicit absence.
- Retained Kafka and Mongo replay support for existing stored events and projections.
- Old enriched-event JSON with duplicate top-level fraud facts can still be deserialized because unknown JSON
  properties are ignored, but those duplicates are not part of the current Java record contract.

Compatibility is narrow and fail-closed. It is not a SOLID violation merely because it exists; ACID durability,
historical replay, and rolling deployment safety take precedence over cosmetic removal.

## Retired Rules Inputs

The current Rules scoring policy consumes current canonical feature facts only. Removed feature-flag,
case-candidate, and top-level duplicate enriched-event facts have been physically removed from the current record
contract; any historical duplicate JSON fields are tolerated only as ignored unknown properties during replay.
