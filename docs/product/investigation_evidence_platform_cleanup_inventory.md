# FDP-81 - Investigation Evidence Platform Cleanup Inventory

Status: current cleanup inventory and deletion governance for the investigation evidence platform.

## Rule

No delete without proof.
No delete by name only.
Legacy does not mean unused.
No behavior change.
No weakened safety boundaries.

FDP-81 starts from discovery. A path, symbol, helper, test, or document is not removable until the proof table below
shows boring search/import/reference evidence and a replacement/current owner or explicit no-owner-needed reason.

## Classification Categories

Use these categories:

1. Domain compatibility
2. Migration/release docs
3. Negative regression guard
4. Current compatibility state
5. Confirmed unused removal candidate
6. Stale docs cleanup
7. Stale frontend cleanup
8. Stale backend cleanup
9. Stale test/helper cleanup
10. Do-not-delete
11. Manual review required

## Initial Classification

| Area | Classification | Current owner | Reason |
| --- | --- | --- | --- |
| Evidence Summary `LEGACY` state | Domain compatibility | Fraud Case Evidence Summary docs and service tests | Active compatibility state for legacy or incomplete linked-alert context. |
| Evidence Timeline `LEGACY_CONTEXT` event | Domain compatibility | Fraud Case Evidence Timeline docs and service tests | Active bounded timeline state, not dead code. |
| Alert read-only detail bridge | Current compatibility state | SuspiciousTransaction internal UI and alert bridge docs/tests | Current read-only linked-alert context path. |
| FraudCase read-surface source guards | Negative regression guard | FDP-78/FDP-80 frontend guard tests | Protect raw payload, raw identifier, workflow, drilldown, and smart-container boundaries. |
| Malicious read-surface fixtures | Negative regression guard | Shared frontend safety tests | Used to prove unsafe text is not rendered. |
| Release and migration docs | Migration/release docs | Release documentation index and branch history | Historical evidence; not deleted by default. |
| FraudCase read-model observability docs/tests | Current compatibility state | FDP-79 docs and metrics tests | Current bounded metric contract. |

## Candidate Review

Weak proof / weak proof means Manual review required. Active references / active references mean Do not delete unless a
stronger replacement test or current owner is named.

| Path/Symbol | Classification | Search proof | Import/reference proof | Current owner/replacement | Decision |
| --- | --- | --- | --- | --- | --- |
| Evidence Summary `LEGACY` / `LEGACY_PROJECTED` | Domain compatibility | `rg "ScoringEvidenceStatus\\.LEGACY\|LEGACY_PROJECTED\|Evidence Summary"` found product docs, projection service tests, docs contract tests, metrics normalization, and suspicious-transaction test support. | Active references exist in `AlertEvidenceSnapshotProjectionService`, `EvidenceProjectionState`, `AlertEvidenceSnapshotProjectionServiceTest`, and `AlertServiceMetrics`. | Current owner: alert-service evidence projection and Fraud Case Evidence Summary docs/tests. | Do not delete. |
| Evidence Timeline `LEGACY_CONTEXT` | Domain compatibility | `rg "LEGACY_CONTEXT"` found product docs, UI fixtures, frontend display/copy allowlists, frontend section behavior, backend enum, backend service, and service tests. | Active references exist in `FraudCaseTimelineEventType`, `FraudCaseTimelineLinkedEntityType`, `FraudCaseEvidenceTimelineService`, `FraudCaseEvidenceTimelineSection`, `fraudCaseTimelineDisplay.js`, and `fraudCaseTimelineCopy.js`. | Current owner: Fraud Case Evidence Timeline backend/frontend contract. | Do not delete. |
| `AlertReadOnlyContextPage` linked-alert bridge path | Current compatibility state | `rg "AlertReadOnlyContextPage\|getAlert\\("` found bridge docs, suspicious-transaction UI docs, page tests, router tests, workspace router import, and backend alert endpoint usage. | Active references exist in `WorkspaceDetailRouter.jsx`, `AlertReadOnlyContextPage.jsx`, `AlertReadOnlyContextPage.test.jsx`, bridge docs contract tests, and suspicious linked-alert docs contract tests. | Current owner: SuspiciousTransaction linked-alert read-only resolver path. | Do not delete. |
| `createAlertReadOnlyBridgeApiClient` legacy helper name | Confirmed unused removal candidate | `rg "createAlertReadOnlyBridgeApiClient\|AlertReadOnlyBridge"` found only a negative source assertion in `AlertReadOnlyContextPage.test.jsx`. | No active helper implementation exists; the only reference is a guard asserting it is not reintroduced. | no-owner-needed: already removed before FDP-81; the negative guard remains the current owner. | Do not delete guard; no deletion needed. |
| FraudCase read-surface raw payload / raw identifier source guards | Negative regression guard | `rg "RawPayload\|JsonInspector\|raw payload\|raw identifier\|getAlert"` found source guards in summary, timeline, layout, investigation read-surface boundary tests, and current AlertDetails usage. | Active references exist in `FraudCaseEvidenceSummarySection.test.jsx`, `FraudCaseEvidenceTimelineSection.test.jsx`, `FraudCaseReadSurfaceLayout.test.jsx`, and `FraudCaseInvestigationReadSurfaceBoundary.test.js`. | Current owner: FDP-78/FDP-80 read-surface safety guards. | Do not delete. |
| Shared malicious investigation read-surface fixtures | Negative regression guard | `rg "maliciousInvestigationRawIdentifiers\|maliciousInvestigationRawPayloadFields\|maliciousInvestigationWorkflowLabels\|maliciousInvestigationVerdictProofText"` found shared fixture and assertion usage in summary and timeline tests. | Active imports exist from `fraudCaseInvestigationReadSurfaceAssertions.js` into `FraudCaseEvidenceSummarySection.test.jsx` and `FraudCaseEvidenceTimelineSection.test.jsx`. | Current owner: shared read-surface safety assertion helpers. | Do not delete unless a stronger replacement test is named. |
| Release and migration docs under `docs/release`, `docs/fdp`, and CI artifact verifiers | Migration/release docs | `rg "release\|migration\|superseded"` found active release governance docs, FDP historical branch docs, CI artifact verification scripts, and GitHub workflow jobs. | Active references exist from `scripts/ci/verify-fdp39-artifacts.mjs`, `scripts/ci/verify-fdp40-artifacts.mjs`, and `.github/workflows/ci.yml`. | Current owner: release governance and historical proof chain. | Do not delete. |
| FDP-50 removed legacy API wrapper docs | Migration/release docs | `rg "removed unused legacy API wrappers\|removes unused legacy wrappers"` found `docs/frontend/api_client_boundary.md` and `docs/fdp/fdp_50_frontend_api_client_boundary.md`. | Active docs remain part of frontend API boundary history and source guard context; no stale duplicate with superseded-by proof was found. | Current owner: API client boundary documentation and guard history. | Do not delete. |
| `unusedRegulatedMutationCoordinator` private test helpers | Manual review required | `rg "unusedRegulatedMutationCoordinator"` found each helper call and private helper declaration in the same fraud-case idempotency integration tests. | Active references exist inside the owning tests; the name means the fake coordinator is intentionally unused by the path under test, not that the helper is dead. | Current owner: fraud-case idempotency regression tests. | Do not delete. |
| `@SuppressWarnings("unused")` test fields and callback parameters | Manual review required | `rg "SuppressWarnings"` found controller, trust, route coverage, and framework callback/test fixture patterns. | Active references are weak by design because reflection/framework/JUnit callbacks can require fields or parameters without direct code calls. | Current owner: individual focused tests; replacement would need a stronger test-by-test proof. | Do not delete by name. |
| `unused-test-private-key` fixture strings | Manual review required | `rg "unused-test-private-key"` found alert-service and fraud-scoring internal-service client production guard tests. | Active references exist in production guard tests; the string is a safe dummy key value, not an unused artifact. | Current owner: internal service client prod guard tests. | Do not delete. |
| `app.audit.external-anchoring.enabled` deprecated compatibility property | Current compatibility state | `rg "deprecated"` found the compatibility warning in `ExternalAuditAnchorSinkConfiguration`. | Active runtime compatibility code still reads the old property and maps it to the current publication settings. | Current owner: external audit anchoring configuration compatibility. | Do not delete. |

## Do-Not-Delete Examples

- LEGACY states used by Evidence Summary / Timeline
- release docs documenting historical migrations
- negative regression tests protecting old unsafe paths
- compatibility docs still linked by current docs index
- source guards protecting raw payload / raw identifier boundaries
- source guards protecting raw payload
- source guards protecting raw identifier
- malicious fixtures used by safety tests
- stronger replacement test must be named before deleting a negative regression guard
- narrow client boundary tests
- old unsafe fallback tests unless stronger replacement is named
- AlertReadOnlyContextPage current resolver path
- FraudCaseEvidenceSummarySection
- FraudCaseEvidenceTimelineSection
- FraudCaseReadSurfaceLayout
- sensitive-read audit tests
- read-model observability tests

## Required Deletion Proof Format

Every deleted item must have an entry:

| Path | Type | Why unused | Search proof | Import/reference proof | Replacement/current owner | Risk | Tests proving no behavior change | Delete in FDP-81 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| No deletions approved in FDP-81 initial cleanup pass | n/a | Discovery has not proven a safe unused artifact yet | n/a | n/a | no-owner-needed: no deletion performed | n/a | Existing behavior tests remain the verification set | No |

Rules:

- Delete only if search/import proof shows no active reference.
- Delete only if replacement/current owner is documented, or no-owner-needed is explicitly justified.
- Do not delete tests unless stronger current test is listed.
- Do not delete docs unless replacement doc or superseded-by owner is listed.
- Do not delete runtime code unless proof is boring and obvious.
- Do not delete release docs by default.
- Do not delete regression guards without replacement.
- Do not treat LEGACY states as dead code.
- No delete by name only; name-based cleanup is not enough.

## Current Confirmed Deletion Candidates

There are no confirmed unused removal candidates in the FDP-81 Stage C discovery pass.

Potential stale artifacts require manual review before deletion if they mention legacy, bridge, read-only, Evidence
Summary, Evidence Timeline, read-surface guardrails, or read-model observability. Name-based cleanup is not enough.

## Discovery Result

No safe deletion candidates were approved in FDP-81.

The cleanup value is:

- roadmap reconciliation
- current-state documentation
- proof-first deletion governance
- do-not-delete classification for active compatibility artifacts
- protection of regression guards
- protection of domain LEGACY states
- protection of release/migration docs

The absence of deletion is intentional. FDP-81 does not delete artifacts without boring search/import/reference proof and
a replacement/current owner or no-owner-needed reason.

## Manual Review Required

- Any file whose only apparent concern is that its name contains legacy.
- Any bridge helper or test that protects the read-only linked-alert context path.
- Any release, branch, or migration document that preserves historical proof.
- Any test helper containing malicious raw identifiers, raw payload text, workflow labels, or verdict/proof wording.
- Any source guard that looks mechanical but protects API client, workflow, drilldown, raw payload, or raw identifier
  boundaries.

## No Behavior Change Verification

The cleanup verification set includes:

- Evidence Summary frontend tests
- Evidence Timeline frontend tests
- read-surface guardrail tests
- FDP-80 composition tests
- AlertReadOnlyContextPage resolver tests
- API client boundary tests
- Evidence Summary read model tests
- Evidence Timeline read model tests
- FraudCase read-model observability tests
- sensitive-read audit tests
- security/authority tests
- mutation regression tests
- docs governance
- frontend build
- backend Maven build
- regulated mutation regression gate
