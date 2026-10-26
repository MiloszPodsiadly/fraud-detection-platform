# Engine Intelligence Contract Version Decision

Status: FDP-149 v2 compatibility design. `common-events` can read nested Engine Intelligence v1 and v2. The producer
remains on v1, and the alert projection/API/React path is not yet ready to consume v2.

## Existing v1 decision

FDP-129 kept `EngineIntelligenceSummary.contractVersion=1` while adding optional `velocity.primary` and explicit
`RULES_VS_ML` comparison identity. That repository-controlled migration covered common-events, fraud-scoring
emission, alert-service projection/read/API DTOs, OpenAPI, and Analyst Console validators.

V1 requires Rules and ML for comparison semantics. `velocity.primary` is an optional third diagnostic engine. When
Velocity is present, canonical order is `rules.primary`, `ml.python.primary`, `velocity.primary`. Duplicate IDs,
wrong ID/type pairs, unknown IDs, four-engine payloads, and out-of-order arrays are invalid. The v1 comparison is
explicitly `RULES_VS_ML` over `[rules.primary, ml.python.primary]`; Velocity never participates in score delta
semantics. Historical identity-free comparisons are rejected, not normalized.

## FDP-149 device extension

The nested summary supports versions 1 and 2. V1 remains exactly Rules, ML, optional Velocity, in that order, with a
maximum of three engines. V2 adds optional `device.primary` / `DEVICE_RISK` after Velocity and allows up to four
engines. Rules and ML remain mandatory. The comparison stays exactly `RULES_VS_ML` over
`[rules.primary, ml.python.primary]`. V2 may omit Device while the optional engine is disabled. Duplicate, unknown,
wrong-type, out-of-order identities, unsupported versions, and version-specific reason misuse fail closed.

Device V2 remains diagnostic only. Its current candidate has no calibrated score or risk level, so `AVAILABLE` is
invalid for the Device result. Device reason codes are `DEVICE_EVIDENCE_NOT_ADMITTED`,
`DEVICE_EVIDENCE_UNAVAILABLE`, `DEVICE_EVIDENCE_PARTIAL`, `DEVICE_EVIDENCE_UNTRUSTED`, and
`DEVICE_SCORE_NOT_CALIBRATED`. Device may also use only these orchestrator codes:
`ORCHESTRATOR_ENGINE_EXCEPTION`, `ORCHESTRATOR_ENGINE_NULL_RESULT`, `ORCHESTRATOR_ENGINE_REJECTED`, and
`ORCHESTRATOR_ENGINE_TIMEOUT`. Device-specific codes are valid only for `device.primary`; existing Rules, ML, and
Velocity reason sets remain unchanged. Existing caps remain five reason
codes per engine, five diagnostic signals, and ten warnings. The engine cap is three for v1 and four for v2.

The v2 serialized summary limit is **16 KiB UTF-8**. `EngineIntelligenceVersionedIdentity.requireSerializedSize`
defines the check. Transport and API ingress/egress must enforce it before parsing or publishing; that wiring belongs
to Prompt 09. Bounded DTO fields do not replace checking actual serialized bytes.

## Compatibility and rollout matrix

| Producer payload | Reader | Result |
| --- | --- | --- |
| Existing producer emits v1 | Existing v1 reader | Supported; current deployment behavior. |
| Existing producer emits v1 | Migrated v1/v2 reader | Supported with unchanged v1 meaning and comparison. |
| Future producer emits v2 | Existing v1-only common-events reader | Rejected by strict nested summary validation. Keep producer disabled until these readers are upgraded or drained. |
| Future producer emits v2 | Current branch common-events v2 reader | Typed v2 summary accepted; the alert projection/API/UI still require migration. |
| V2 payload has missing, malformed, conflicting, or unknown version/identity | Strict reader | Rejected or quarantined under the consumer's explicit disposition; never downgraded or repaired as v1. |
| Retained v1 Kafka, DLT, Mongo projection, feedback, or API data | Migrated v1/v2 reader | Continue v1 interpretation; no backfill or invented Device result. |
| Retained v2 Kafka/DLT/API data | Reader downgraded to v1-only | Downgrade is blocked until v2 data is drained, archived, or terminally quarantined under approved recovery. |

## Repository consumers and deployment gate

The reviewed paths are:

- Shared contract and strict outer event parser: `common-events` `EngineIntelligenceSummary` and
  `TransactionScoredEvent`.
- Producer: fraud-scoring `PublicEngineIntelligenceMapper` and `TransactionScoredEventMapper`.
- Projection: alert-service `EngineIntelligenceProjectionPolicy` and `EngineIntelligenceProjectionService`.
- Retained read data: feedback read model, feedback dataset mapper/export, and Mongo projections.
- API and schema: `EngineIntelligenceResponse`, `ScoredTransactionDetailResponse`, and alert-service OpenAPI.
- Analyst Console: `engineIntelligenceContractValidation.js` and diagnostic rendering.
- Kafka and recovery: authoritative, projection, and redrive listeners deserialize the full scored-event contract;
  DLT, redrive, and replay retain versioned payloads.

The current alert projection explicitly requires `EngineIntelligenceSummary.CONTRACT_VERSION` (v1), and API/UI
validators also accept only v1. Those paths need migration and tests before producer v2 emission. The current branch
common contract is backward-reading only; no v2 producer flag is enabled by this ADR.

The outer `TransactionScoredEvent.eventContractVersion=2` and Engine Intelligence recovery provenance version are
separate contracts and remain unchanged. Rollout order is consumer-first: deploy v1/v2 Kafka, API, projection, and UI
readers; verify replay and retained-data handling; then separately approve producer v2 emission. Rollback first
disables v2 emission. Reader downgrade requires checking Kafka retention, every DLT/redrive topic, Mongo current
projections, feedback records/exports, API caches, and replay archives for v2 data. Unknown external consumers remain
a deployment blocker until their support is confirmed.
