# Fraud Intelligence Non-Goals

Status: current fraud-intelligence non-goals with historical FDP-82 foundation notes.

## Scope

FDP-82 created the shared engine-result vocabulary for the analyst-assisted fraud intelligence platform. Current
Engine Intelligence adds bounded diagnostic runtime, event summary, alert-service projection, read API/OpenAPI, and
Analyst Console rendering, but it still does not enable final decisioning or payment authorization.

## Explicit Non-Goals

This foundation has:

- no automatic decline;
- no automatic approve;
- no automatic blocking of a transaction;
- no core banking authorization;
- no final payment decision;
- no bank-certified production decision claim;
- no ML final decision source;
- no weighted ensemble in this branch;
- no model promotion workflow in this branch.

## Runtime Boundaries

Current Engine Intelligence does not expose raw `FraudEngineResult`, publish public `engineResults[]`, store raw
engine payloads, change baseline scoring mode, create a weighted ensemble, or turn diagnostics into final decisions.
Historical FDP-82 did not add orchestration, Kafka event changes, alert-service projection, API, UI, feedback storage,
or scoring-mode changes; those branch-specific exclusions are superseded by later scoped Engine Intelligence work.

An engine result is not a final banking decision. It is bounded analyst investigation context.
