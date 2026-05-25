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

Keys representing raw payloads, request/response bodies, headers, authorization, tokens/secrets,
passwords, stack traces/exception text, debug metadata, PAN/card/account identifiers,
SSN/national identifiers, email/phone, raw device fingerprints, raw user-agent values, or
host/endpoint/url values are forbidden.

## Value Semantics

- `PRESENT` means the key exists and its value exactly matches the requested scalar accessor type.
- `MISSING` means the key is absent.
- `INVALID_TYPE` means the key exists but its value does not match the requested scalar accessor type.
- `NOT_ALLOWED` means the key is forbidden or outside policy.

A missing boolean is not false. A missing number is not zero. A missing string is not empty
string. An invalid type is not coerced: string `"true"` is not boolean `true`, string `"3"` is
not integer `3`, and integer `1` is not boolean `true`. `NOT_ALLOWED` is an explicit outcome and
must not be silently ignored by future adapters.

No `UNAVAILABLE` status is defined in FDP-85 because current upstream feature computation does
not provide an explicit unavailable marker. That semantic can be added only when upstream
feature computation explicitly supports it.

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
