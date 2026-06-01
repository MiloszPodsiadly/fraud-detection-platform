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
| Producer boundary | `TransactionFraudScoringService` -> `TransactionScoredEventMapper` -> `KafkaTransactionScoredEventPublisher` | Mechanically guarded to keep the old emitted shape |
| Test fixture helper | `common-test-support` `TransactionFixtures` | Existing test-only builder remains documented separately |
| Integration tests | `AlertServiceIntegrationTest`, `FraudDetectionPlatformEndToEndIntegrationTest`, and `FraudScoringIntegrationTest` | Existing scored-event integration coverage remains in place |
| Replay and smoke scripts | Repository search found raw-transaction replay input only; no scored-event fixture reader was found | Shared FDP-93 fixtures are the scored-event compatibility source |
| API/UI | Repository search found no direct `TransactionScoredEvent` deserializer in API or analyst console UI | Guarded against product exposure |

The source-scan discovery test fails with `TRANSACTION_SCORED_EVENT_CONSUMER_INVENTORY_REVIEW_REQUIRED`
when a production reference is added without inventory review.

## Fixture Strategy

Shared fixtures live under `common-events/src/test/resources/fixtures/transaction-scored-event/`.
They cover the old event shape, a minimal bounded summary, the full bounded summary, unknown nested
summary fields, and an unknown top-level event field.

## Alert-service Readiness

Alert-service may prove deserialization readiness only. Its tests use the Spring Kafka
`JsonDeserializer` used by the listener boundary and verify that existing projection output remains
unchanged.

## Unknown-field Tolerance

The shared event contract and public engine-intelligence DTOs ignore unknown fields. Alert-service
tests prove tolerance for both unknown nested engine-intelligence fields and an unknown top-level
event field.

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
