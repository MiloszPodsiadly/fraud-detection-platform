# FDP-80 - Fraud Case Read Surface Composition Cleanup

Status: current frontend composition contract for FraudCase read-only investigation context.

## Purpose

FDP-80 keeps FraudCaseDetailsPage composition clear after Evidence Summary, Evidence Timeline, shared guardrails, and
read-model metrics are in place.

Core rule:

- Compose read-only investigation context clearly.
- Keep read-only sections separate.
- Keep workflow outside.
- Do not add behavior.
- Do not weaken boundaries.

## Scope IN

- frontend-only composition cleanup
- optional presentational layout wrapper
- Evidence Summary remains separate
- Evidence Timeline remains separate
- workflow/decision rail remains outside read-only surface
- no new rendered data
- no new API calls
- docs and tests

## Scope OUT

- no backend changes
- no DTO changes
- no endpoint changes
- no metrics changes
- no new API client methods
- no tabs
- no accordion/collapse behavior
- no routing changes
- no feature flags
- no drilldowns
- no workflow actions
- no analyst decision changes
- no final outcome
- no combined smart InvestigationPanel
- no raw payload or raw identifier rendering
- no redesign

## Layout Rule

A layout wrapper may exist only as a presentational wrapper. It must not fetch, receive apiClient, receive workflow
handlers, own data, or merge Summary and Timeline semantics.

`FraudCaseReadSurfaceLayout` may group existing read-only sections for composition clarity. It does not add visible fields, tabs, controls, links, forms, drilldowns, persisted preferences, route state, or product behavior.

Summary and Timeline keep their independent fetch dependencies, helper text, loading states, empty states, and
unavailable states. Failure in one section must not hide the other section.

## Workflow Boundary

FraudCaseDetailsPage may keep existing workflow and decision controls outside the read-only investigation context.
FDP-80 does not redefine the full page as read-only, and it does not move Save, Submit, Decision, Close, Reopen,
assignment, or claim controls into read-only sections.

## Non-claims

FDP-80 is not:

- a new product feature
- a redesign
- tabs
- dashboard
- workflow change
- decision UI change
- evidence drilldown
- alert drilldown
- smart investigation panel

## Verification

- DocsMentionReadSurfaceCompositionCleanupTest
- DocsMentionNoSmartInvestigationPanelTest
- DocsMentionWorkflowOutsideReadSurfaceTest
- DocsMentionNoNewBehaviorTest
- DocsMentionNoTabsOrRedesignTest

Existing FDP-75 Evidence Summary tests, FDP-77 Evidence Timeline tests, and FDP-78 read-surface guardrails remain in
force. FDP-80 is composition-only and must not weaken those contracts.
