# Feature Snapshot Consumption Policy

Status: FDP-85 internal adapter-consumption policy only.

## Purpose

This document defines how future `FraudSignalEngine` adapters may safely consume
`ScoringContext.featureSnapshot`. FDP-82 defines `FraudEngineResult` as the output boundary,
FDP-83 defines `ScoringContext` as the input boundary, FDP-84 defines `FraudSignalEngine` as
the internal runtime interface, and FDP-85 defines safe feature snapshot reading. FDP-85 does
not add adapters.

## Boundary

`featureSnapshot` remains internal to `fraud-scoring-service`. It is not a Kafka event, not an
API DTO, not a storage document, not a public cross-service contract, and not a source of truth.
`FeatureSnapshotReader` is an internal policy/accessor layer only. It does not compute features,
does not score risk, does not infer fraud, and does not normalize business meaning. This branch
introduces no runtime scoring behavior change, no event/API/UI change, and no projection change.

## Key Naming And Allowlist

The repository already defines canonical feature keys in `FraudFeatureContract`, and those keys
are established `camelCase` identifiers such as `deviceNovelty`, `recentAmountSumPln`, and
`rapidTransferFraudCaseCandidate`. FDP-85 therefore selects compatibility with the registered
`camelCase` contract. It does not create dot-separated aliases or normalize one naming convention
into another.

`FeatureSnapshotKeyPolicy` permits only currently registered safe keys from the existing
`FraudFeatureContract` feature name lists. New keys require an intentional contract update before
they can be consumed by a future adapter.

`isAllowedFeatureKey` is not adapter-consumption permission. It means the key is a known feature
contract key that passed safety checks, not necessarily scalar adapter-consumable. Future adapters
must use `FeatureSnapshotReader` or `expectedTypeFor`, not `isAllowedFeatureKey` alone.

Keys representing raw payloads, request/response bodies, headers, authorization, tokens/secrets,
passwords, stack traces/exception text, debug metadata, PAN/card/account identifiers,
SSN/national identifiers, email/phone, raw device fingerprints, raw user-agent values, or
host/endpoint/url values are forbidden.

`requireAllowedFeatureKey` exceptions must not expose raw rejected keys, including oversized or
sensitive key input.

## Key And Type Consumption Policy

Adapter consumption is not key-only. A feature is consumable only when both the key and expected
scalar type are approved by policy. A registered `FraudFeatureContract` key does not automatically
mean scalar adapter-consumable. Some registered keys are intentionally not consumable by the v1
scalar reader.

Examples:

- `deviceNovelty` is boolean.
- `recentTransactionCount` is integer.
- `transactionVelocityPerMinute` is double.
- `currency` is string.
- `rapidTransferTotalPln` is decimal.
- `rapidTransferTransactionIds` is not consumable by v1 scalar reader.
- `featureFlags` is not consumable by v1 scalar reader.

Wrong accessor is not valid consumption. `stringValue("deviceNovelty")` is not a valid way to read
a boolean feature. `booleanValue("currency")` is not a valid way to read a string feature.

## Value Semantics

- `PRESENT` means the key exists and its value exactly matches the requested scalar accessor type.
- `MISSING` means the key is absent.
- `INVALID_TYPE` means the key exists and the accessor matches policy, but the actual runtime value
  type does not match the expected Java class.
- `WRONG_ACCESSOR` means the feature key is scalar-consumable, but the caller used an accessor that
  does not match the policy-declared scalar type.
- `NOT_ALLOWED` means the key is forbidden or outside policy.

A missing boolean is not false. A missing number is not zero. A missing string is not empty
string. An invalid type is not coerced: string `"true"` is not boolean `true`, string `"3"` is
not integer `3`, and integer `1` is not boolean `true`. `NOT_ALLOWED` is an explicit outcome and
must not be silently ignored by future adapters. `NOT_ALLOWED` results must not expose raw rejected
keys.

No `UNAVAILABLE` status is defined in FDP-85 because current upstream feature computation does
not provide an explicit unavailable marker. That semantic can be added only when upstream
feature computation explicitly supports it.

## Reader Constructor Policy

`FeatureSnapshotReader` accepts the existing internal snapshot shape. The constructor rejects null
maps, top-level null keys, and top-level null values, then defensively copies the full top-level
map. It does not filter or reject every disallowed key because upstream snapshots may contain data
not intended for adapter consumption.

Adapter consumption is controlled at read time by `FeatureSnapshotKeyPolicy`. Disallowed or
unregistered keys return `NOT_ALLOWED`, and disallowed keys are not exposed as raw output.
`NOT_ALLOWED` results must not expose raw rejected keys. The raw feature map is never exposed.
Future adapters must not inspect the raw map directly.

## Evidence And Contribution Safety

Some scalar string features such as `customerSegment` and `merchantCategory` may be valid internal
features. Reading a string feature internally does not authorize exposing the raw value in
`FraudEngineResult` evidence, analyst explanations, logs, metrics, or UI.

Future adapters must use a bounded/safe evidence policy before including feature values in engine
outputs. Evidence should prefer bounded labels, reason codes, or safe contribution identifiers
rather than raw feature values. This belongs to future adapter/result evidence policy, not FDP-85.

## Null And Nested Values

Top-level null keys are invalid. Top-level null values are invalid. Unknown/unavailable values
must not be represented by null. The reader copies the top-level map defensively and does not
expose that raw map.

Arbitrary nested structures are not consumed by adapters in v1. Nested `Map` or `List` values
are not scalar features and scalar accessors return `INVALID_TYPE` for them. Future nested
feature support requires an explicit policy and tests.

## Runtime Isolation

FDP-85 contains no `RuleBasedSignalEngine`, no `PythonMlSignalEngine`, no
`FraudScoringOrchestrator`, no `FraudIntelligenceResult`, no `engineResults[]`, and no event/API/UI
integration. It does not wire `FeatureSnapshotReader` into `CompositeFraudScoringEngine` and does
not change current rule-based or ML scoring behavior.

Future adapters must use `FeatureSnapshotReader` and the policy layer. They must not directly
cast values from `Map<String, Object>` or call `context.featureSnapshot().get(...)` directly.

## Next Branch

FDP-86 may add `RuleBasedSignalEngine` only after it uses the policy/accessor and does not read
`context.featureSnapshot().get(...)` directly.
