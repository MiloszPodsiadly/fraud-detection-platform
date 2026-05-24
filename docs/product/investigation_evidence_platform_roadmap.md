# FDP-81 - Investigation Evidence Platform Roadmap Reconciliation

Status: current FDP-81 cleanup milestone documentation.

## Current Phase

The FDP-56-79 evidence/readiness/metrics backbone is complete.
FDP-80 closes post-milestone read-surface composition cleanup.
FDP-81 performs full cleanup after the evidence platform milestone.

FDP-81 is a cleanup and roadmap reconciliation branch. It does not implement a new runtime feature, new endpoint, new
DTO, new authority, new metric contract, or new product workflow.

## Historical Plan Mapping

Historical roadmap items map to the implemented FDP-73-80 chain as follows:

| Historical roadmap item | Actual implementation owner |
| --- | --- |
| Evidence Summary backend | FDP-73 |
| Evidence Summary UI | FDP-74 |
| Evidence Summary UI hardening | FDP-75 |
| Evidence Timeline backend | FDP-76 |
| Evidence Timeline UI | FDP-77 |
| Shared read-surface guardrails | FDP-78 |
| FraudCase read-model observability | FDP-79 |
| Read-surface composition cleanup | FDP-80 |
| Full cleanup / roadmap reconciliation | FDP-81 |

Earlier FDP-56-72 work remains the supporting evidence, suspicious-transaction, security, and linked-context foundation
for this platform, but the current FraudCase evidence platform user-facing chain is FDP-73 through FDP-80.

## Completed Platform Capabilities

- bounded FraudCase evidence summary
- bounded FraudCase evidence timeline
- read-only UI surfaces
- shared read-surface guardrails
- backend read-model observability
- composition boundary for read surfaces
- roadmap and cleanup governance

## Not Yet Product Scope

- tabs
- drilldowns
- final outcome UX
- false-positive management
- workflow redesign
- decision rail redesign
- dashboard/alerting policy
- raw evidence viewer
- model/rule performance product view

## Next Product Wave Candidates

These are candidates only. FDP-81 does not implement them:

- Final Outcome Semantics / UI
- False Positive Management
- Rule/Model Performance Read Model
- Case Type Taxonomy
- Investigation Context Navigation

## Interpretation Rules

- Historical branch documents remain evidence unless a current product, architecture, API, security, or runbook source
  explicitly supersedes them.
- A future branch must open explicit product scope before adding tabs, drilldowns, final outcome UX, workflow redesign,
  or raw evidence views.
- Cleanup must not weaken evidence safety boundaries, read-only section boundaries, sensitive-read audit behavior, or
  read-model observability behavior.
