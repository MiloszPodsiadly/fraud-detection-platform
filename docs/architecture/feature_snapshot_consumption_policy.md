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
`recentAmountSumPln` and `rapidTransferThresholdPln` must round-trip as `BigDecimal`; integer, double, boolean, and
string facts must round-trip as their declared scalar types.

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

Examples:

- `deviceNovelty` is boolean.
- `recentTransactionCount` is integer.
- `transactionVelocityPerMinute` is double.
- `currency` is string.
- `rapidTransferTotalPln` is decimal when read by historical consumers.
- `rapidTransferTransactionIds` is not consumable by current Rules scoring.
- `featureFlags` is not consumable by current Rules scoring.

Wrong accessor use is not valid consumption. `stringValue("deviceNovelty")` and
`booleanValue("currency")` must fail with bounded status rather than silently coercing data.

## Canonical-Versus-Legacy Precedence

Current canonical feature-snapshot values are authoritative for new scoring. When a canonical field required by an
adapter is present and valid, the adapter uses it. When that canonical field is present but invalid, wrong-typed, out
of domain, nested, or contradictory, the adapter must fail closed. It must not activate a legacy fallback and must
not publish a fake low-risk or zero-score result.

Historical replay compatibility belongs to explicit event/read compatibility boundaries. It must not be implemented
as a current Rules scoring fallback from retired flags, retired rapid-transfer candidates, or retired top-level
duplicate facts.

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

Velocity V1 requires `recentTransactionCountWindow=PT1M`; producer meaning, consumer validation, and policy meaning
all use that one-minute observation window. Changing the window requires a versioned contract and policy update.
Velocity validates count/window/rate consistency and degrades on impossible present values instead of silently
choosing one fact. Velocity remains optional, diagnostic-only, and not a calibrated probability, final decision,
payment authorization, case action, threshold recommendation, or analyst recommendation source.

Velocity V1 does not consume retired rapid-transfer candidate facts as its primary policy input; rapid-transfer
threshold semantics are owned by `FraudFeatureThresholdContract`.

## Rules Canonical Input Policy

Rules V2 uses canonical feature-snapshot facts only. Required input is:
`recentTransactionCount`, `recentTransactionCountWindow`, `transactionVelocityPerMinute`,
`recentAmountSumPln`, `recentAmountSumWindow`, `currentTransactionAmountPln`, `merchantFrequency7d`,
`deviceNovelty`, `countryMismatch`, `proxyOrVpnDetected`, and `currency`.

Present-invalid canonical Rules inputs fail closed: string counts, string decimals, negative counts, negative
amounts, invalid or missing required canonical time windows, nested values, booleans used as numbers, and oversized
numeric input must not become `AVAILABLE LOW` and must not fall back to retired flags or candidate fields.

Rules V2 time-dependent canonical facts use the explicit `PT1M` window. `recentTransactionCount` requires
`recentTransactionCountWindow=PT1M`; `recentAmountSumPln` requires `recentAmountSumWindow=PT1M`. Present-invalid
window data is canonical corruption, not compatibility.

Valid canonical facts are authoritative. Predicate false is not missing: a false canonical high-amount or rapid
burst predicate cannot be overridden by retired `HIGH_AMOUNT_ACTIVITY`, retired `RAPID_PLN_20K_BURST`, or
`rapidTransferFraudCaseCandidate`. PLN thresholds consume canonical PLN facts only.

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

- `EngineIntelligenceComparisonV1Compatibility` for historical comparison objects that contain the complete legacy
  semantic triplet.
- Old-event `engineIntelligence == null` handling so historical Kafka events remain readable as explicit absence.
- Retained Kafka and Mongo replay support for existing stored events and projections.
- Source-compatible constructors used by repository-controlled consumers during staged rollout.

Compatibility is narrow and fail-closed. It is not a SOLID violation merely because it exists; ACID durability,
historical replay, and rolling deployment safety take precedence over cosmetic removal.

## Retired Rules Inputs

The current Rules scoring policy must not consume retired policy feature flags, `rapidTransferFraudCaseCandidate`,
retired rapid-transfer threshold/count/total facts, or top-level duplicate event facts as scoring input. Those fields
may remain readable only at explicit event compatibility boundaries until a separate Kafka/storage contract migration
physically removes them.
