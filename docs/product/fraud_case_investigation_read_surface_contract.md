# FDP-78 - Fraud Case Investigation Read Surface Contract Hardening

## Purpose

FDP-78 defines shared frontend safety guardrails for FraudCase read-only investigation surfaces:

- Evidence Summary
- Evidence Timeline

These surfaces explain investigation context. They do not mutate, decide, prove, expose raw payloads, or become workflow
surfaces.

## Scope IN

- shared test assertions
- shared malicious fixtures
- section-scoped source/import guards
- docs contract
- no runtime behavior change

## Scope OUT

- no backend changes
- no DTO changes
- no endpoint changes
- no new authority
- no new API client methods
- no new rendered fields
- no visual redesign
- no drilldowns
- no workflow changes
- no combined smart InvestigationPanel

FDP-78 does not introduce a new runtime security layer. It hardens the frontend contract through shared tests,
malicious fixtures, source/import boundary guards, and documentation. Runtime rendering behavior of Evidence Summary
and Evidence Timeline remains unchanged.

## Read-only section contract

Each read-only investigation section must:

- receive a narrow fetch function, not full apiClient
- render bounded context only
- avoid raw identifiers
- avoid raw payloads
- avoid raw backend title/description
- avoid JSON inspectors
- avoid drilldowns
- avoid mutation controls
- avoid workflow controls
- avoid analyst decision controls
- avoid final outcome/proof/verdict wording

## Section-scoped testing

Tests must assert safety on the section container, not the entire FraudCaseDetailsPage.

Reason:
FraudCaseDetailsPage may legally contain existing workflow controls outside Evidence Summary and Evidence Timeline.
FDP-78 does not redefine the whole page as read-only.

## Source/import boundary guards

Source/import boundary guards are governance tripwires. They intentionally use strict source-level checks to catch
common regressions such as full apiClient usage, workflow imports, drilldown imports, raw field rendering, and raw
backend title/description access.

They are not a formal semantic static analysis system. They may produce false positives on harmless comments or helper
names, and future maintainers should treat such failures as a prompt for a conscious review rather than silently
weakening the guard.

The guards are section-scoped and must not inspect the whole FraudCaseDetailsPage, because workflow controls may
legally exist outside Evidence Summary and Evidence Timeline.

## Non-claims

Read surfaces are not:

- proof of fraud
- fraud verdict
- final outcome
- analyst decision
- legal proof
- audit trail
- complete case history
- workflow surface

## Merge gate

- Frontend-only.
- No backend changes.
- No DTO changes.
- No endpoint changes.
- No new authority.
- No new API client methods.
- No new rendered fields.
- No runtime behavior change.
- Evidence Summary behavior unchanged.
- Evidence Timeline behavior unchanged.
- Existing FDP-75/FDP-77 tests are not weakened.
- Shared helpers are additive.
- Section-specific malicious fixtures remain where they catch unique risks.
- Source guards are scoped to read-only sections, not whole FraudCaseDetailsPage.
- Existing FraudCase detail workflow controls outside read-only sections are not redefined.
- Evidence Summary does not receive full apiClient.
- Evidence Timeline does not receive full apiClient.
- Both sections use narrow fetch functions only.
- Both sections do not render raw payloads.
- Both sections do not render raw identifiers.
- Both sections do not render raw backend title/description.
- Both sections do not render JSON inspector.
- Both sections do not render alert/evidence drilldowns.
- Both sections do not render workflow controls.
- Both sections do not render analyst decision controls.
- Both sections do not render final outcome/proof/verdict wording.
