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

FDP-80 does not redesign Summary or Timeline state rendering. Existing section-owned loading, empty, unavailable, and
helper-text behavior remains owned by the individual section contracts. FDP-80 only verifies composition-level behavior:
both sections stay separate, helper text remains visible, and failure in one section does not hide the other.

The layout wrapper may expose a bounded accessibility label for screen readers. This accessibility label is not a visible product field, does not add investigation data, and must not introduce workflow/decision semantics.

The `fraudCaseReadSurfaceLayout` CSS class is currently a composition/test boundary. It may be used later only for
minor spacing or container styling. Future styling must not hide, collapse, reorder, or visually de-emphasize Summary,
Timeline, or their required non-claim helper text. Any tabs, collapse, navigation, or redesign must be a separate
explicit feature scope.

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
- DocsMentionSourceGuardsAreTripwiresTest
- DocsMentionStateConsistencyIsSectionOwnedTest
- DocsMentionAccessibilityLabelIsNotVisibleProductFieldTest

Existing FDP-75 Evidence Summary tests, FDP-77 Evidence Timeline tests, and FDP-78 read-surface guardrails remain in
force. FDP-80 is composition-only and must not weaken those contracts.

## Source Guard Limitations

Source-level guards are governance tripwires. They intentionally use strict string checks to catch common regressions
such as turning the presentational wrapper into a smart container, adding workflow/action terminology, adding API client
usage, or introducing tabs/drilldowns.

They are not formal semantic static analysis. They may produce false positives on harmless comments or helper names,
and they may not catch every possible unsafe implementation if names are changed.

Runtime behavior tests remain the primary evidence for FDP-80 composition behavior:

- Summary and Timeline remain separate.
- Workflow controls stay outside the read surface.
- Failure in one section does not hide the other.
- Narrow fetch dependencies remain.
