# Fraud Intelligence Non-Goals

Status: current boundary for the FDP-82 contract foundation.

## Scope

FDP-82 creates a shared engine-result vocabulary for the analyst-assisted fraud intelligence platform. It does not
enable new runtime scoring behavior.

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

## Runtime Exclusions

This branch does not add orchestration, change a Kafka scored transaction event, project engine results into
`alert-service`, expose an API, display UI, store feedback, or change any existing scoring mode.

An engine result is not a final banking decision. It is bounded analyst investigation context.
