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

## Do-Not-Delete Examples

- LEGACY states used by Evidence Summary / Timeline
- release docs documenting historical migrations
- negative regression tests protecting old unsafe paths
- compatibility docs still linked by current docs index
- source guards protecting raw payload / raw identifier boundaries
- malicious fixtures used by safety tests
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

## Current Confirmed Deletion Candidates

There are no confirmed unused removal candidates in the FDP-81 initial cleanup pass.

Potential stale artifacts require manual review before deletion if they mention legacy, bridge, read-only, Evidence
Summary, Evidence Timeline, read-surface guardrails, or read-model observability. Name-based cleanup is not enough.

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
