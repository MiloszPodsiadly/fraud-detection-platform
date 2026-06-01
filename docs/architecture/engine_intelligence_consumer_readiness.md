# Consumer-first Engine Intelligence Rollout Readiness

Status: FDP-93 consumer-readiness foundation only.

## Purpose

FDP-93 proves consumers can safely tolerate engineIntelligence before any producer emits it.
Do not emit what consumers have not proven they can safely tolerate.
FDP-93 is consumer-readiness, not product exposure.

## Consumer Inventory

| Area | Known consumer or usage path | FDP-93 treatment |
| --- | --- | --- |
| Shared contract | `common-events` `TransactionScoredEvent` and compatibility tests | Shared old, minimal, full-bounded, unknown-nested, and unknown-top-level fixtures |
| Alert Kafka consumer | `AlertKafkaConfig` -> `TransactionScoredEventListener` | Kafka `JsonDeserializer` compatibility proof |
| Alert monitoring projection | `TransactionMonitoringService` -> `ScoredTransactionDocumentMapper` -> `ScoredTransactionDocument` | Existing projection compared against the old event shape |
| Alert creation path | `AlertManagementService` -> `AlertCaseFactory` -> `AlertDocument` | Inventory only; no engine-intelligence projection |
| Fraud-case path | `FraudCaseManagementService` -> `FraudCaseDocument` and `FraudCaseTransactionDocument` | Inventory only; no engine-intelligence projection |
| Suspicious transaction path | `SuspiciousTransactionProjectionService` -> `SuspiciousTransactionDocument` | Inventory only; no engine-intelligence projection |
| Evidence paths | `EvidenceProjectionService` and `AlertEvidenceSnapshotProjectionService` | Inventory only; no engine-intelligence projection |
| Producer boundary | `TransactionFraudScoringService` -> `TransactionScoredEventMapper` -> `KafkaTransactionScoredEventPublisher` | FDP-94 adds a controlled optional public mapper capability while the live service remains mechanically guarded to keep the old emitted shape |
| Test fixture helper | `common-test-support` `TransactionFixtures` | Existing test-only builder remains documented separately |
| Integration tests | `AlertServiceIntegrationTest`, `FraudDetectionPlatformEndToEndIntegrationTest`, and `FraudScoringIntegrationTest` | Existing scored-event integration coverage remains in place |
| Replay and smoke scripts | Repository search found raw-transaction replay input only; no scored-event fixture reader was found | Shared FDP-93 fixtures are the scored-event compatibility source |
| API/UI | Repository search found no direct `TransactionScoredEvent` deserializer in API or analyst console UI | Guarded against product exposure |

The source-scan discovery test fails with `TRANSACTION_SCORED_EVENT_CONSUMER_INVENTORY_REVIEW_REQUIRED`
when a production reference is added without inventory review.
Source-scan guards are intentionally strict and may require updates when production references move
or new consumers are added. A source-scan failure means consumer inventory review is required.
Source-scan guards are not a substitute for architectural review. New TransactionScoredEvent
consumers must be added to the inventory intentionally. The bounded failure message is
`TRANSACTION_SCORED_EVENT_CONSUMER_INVENTORY_REVIEW_REQUIRED`.

## Fixture Strategy

Shared fixtures live under `common-events/src/test/resources/fixtures/transaction-scored-event/`.
They cover the old event shape, a minimal bounded summary, the full bounded summary, unknown nested
summary fields, and an unknown top-level event field.
Fixture name prefix v1/v2 describes the TransactionScoredEvent fixture shape used for compatibility tests.
`v1_without_engine_intelligence` means the pre-FDP-92 scored-event shape.
`v2_*_engine_intelligence` means the scored-event shape with optional engineIntelligence present.
This does not change `EngineIntelligenceSummary.contractVersion`.
The nested public engine-intelligence contract remains `contractVersion = 1`.

## Alert-service Readiness

Alert-service may prove deserialization readiness only. Its tests use the Spring Kafka
`JsonDeserializer` used by the listener boundary and verify that existing projection output remains
unchanged.

## Unknown-field Tolerance

The shared event contract and public engine-intelligence DTOs ignore unknown fields. Alert-service
tests prove tolerance for both unknown nested engine-intelligence fields and an unknown top-level
event field.
FDP-93 intentionally requires alert-service tolerance for unknown top-level TransactionScoredEvent
fields as a forward-compatibility guardrail, not only for engineIntelligence nested fields.
Unknown top-level tolerance helps future additive event evolution.
Unknown top-level tolerance does not authorize producers to emit arbitrary fields without contract review.
Producer branches must still define exact public payload shape.
Future producer emission must keep strict producer-side contract tests.
Consumer tolerance is not producer looseness.

## Payload Tolerance

FDP-92 proves the DTO is bounded.
FDP-93 proves consumers tolerate the bounded DTO.
Alert-service deserializes the full bounded fixture without expanding its projection.

## No Projection / No Persistence Boundary

FDP-93 does not add alert-service projection.
FDP-93 does not persist engineIntelligence.
Projection requires separate review.

## No Producer Emission Boundary

FDP-93 does not emit engineIntelligence in production runtime.
Producer emission must be a separate branch.
Producer emission requires consumer-readiness proof.

## Future Producer-emission Feature Flag Requirement

Future producer emission must be disabled by default and guarded by an explicit feature flag.
Do not implement the feature flag in FDP-93.

FDP-94 adds the separately reviewed disabled-by-default runtime producer emission documented in
[Controlled engine intelligence producer emission rollout](engine_intelligence_producer_emission_rollout.md).
It does not migrate baseline scoring decisions to the orchestrator and does not enable production
runtime emission by default. Explicit `true` enables separate diagnostic enrichment only.

## Future Producer Emission Gate

Future producer emission of engineIntelligence must be disabled by default.
Producer emission requires an explicit rollout flag.
Producer emission must not be enabled until FDP-93 consumer-readiness tests are green.
Old event shape must remain the default until rollout is explicitly enabled.
Producer tests in the future branch must cover enabled and disabled modes.
Producer emission must preserve FDP-92 public contract semantics.
Producer emission must not introduce final decisioning.
Producer emission must not combine projection/API/UI in the same branch unless explicitly scoped and reviewed.

## Merge Gates

- Shared fixtures deserialize in `common-events`.
- Alert-service deserializes old and new fixture shapes through its Kafka deserializer boundary.
- Alert-service projection remains unchanged for the new and forward-compatible fixture shapes.
- Source scans remain green for consumer inventory, producer isolation, persistence isolation, and API/UI isolation.
- FDP-93 does not expose engineIntelligence through API/UI.
- FDP-93 does not add final decisioning.

## Future Roadmap

A separate reviewed branch may add producer emission behind a disabled-by-default rollout flag after
consumer-readiness proof. Any projection, persistence, or API/UI work requires a separate scope and
review.
FDP-93 fixtures cover valid and forward-compatible event shapes.
Invalid nested engineIntelligence versions remain a future producer/contract-validation hardening case.
Future producer emission branch should test that unsupported engineIntelligence contract versions fail safely and boundedly.
Invalid-version handling must not be interpreted as consumer tolerance.
