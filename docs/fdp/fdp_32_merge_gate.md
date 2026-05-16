# FDP-32 Merge Gate

Status: branch evidence.


## Problem

Regulated mutation command execution must not allow a stale worker to persist command state or execute local business mutation after losing lease ownership.

## Decision

The merge gate for FDP-32 is the lease-fencing invariant plus pre-mutation active lease validation. Claim acquisition remains separate from write fencing.

FDP-32 is merge-safe as lease-owner fenced command transition hardening. It is not by itself production enablement for FDP-29 or any external-finality claim.

## Scope

This gate applies to regulated mutation executors in `alert-service`. It does not change public API statuses, broker contracts, or production feature enablement.

## Components

- `RegulatedMutationClaimService`
- `RegulatedMutationFencedCommandWriter`
- `LegacyRegulatedMutationExecutor`
- `EvidenceGatedFinalizeExecutor`
- regulated mutation replay policies

## Command Transition Fencing Invariant

All claimed command transitions must use `RegulatedMutationFencedCommandWriter`. Executors must not use `commandRepository.save(...)` for command state transitions.

## Business Mutation Safety Boundary

Executors must validate active lease before invoking `command.mutation().execute(...)`. Command transition fencing is not business-side-effect rollback by itself. Production stale-worker business-write safety requires transaction-mode REQUIRED or an equivalent domain idempotency guard.

## ACID Boundary

FDP-32 relies on the existing local Mongo transaction boundary. Transaction-mode OFF is compatibility behavior and is not a bank-grade stale-worker rollback mode.

## Non-Goals

- no distributed lock
- no distributed ACID
- no external finality
- no heartbeat extension
- no process-kill chaos proof
- no new public statuses
- no FDP-29 production/bank enablement
- no Kafka or outbox contract change

## Merge OK Requirements

- full CI green
- FDP-29 integration tests green
- FDP-31 claim/replay tests green
- FDP-32 fencing tests green
- stale-worker executor integration tests green
- no `commandRepository.save(...)` in claimed executor transitions
- feature flags unchanged
- no public API status changes
- docs preserve non-claims: no distributed lock, no distributed ACID, no external finality, no process-kill chaos proof, and no heartbeat renewal

## Failure Behavior

Stale lease validation must reject before business mutation. Rejected stale workers must not persist response snapshots, outbox ids, local commit markers, or success audit flags. Conditional recovery write conflicts must fail explicitly.

## Observability

The gate requires bounded metrics for stale write rejection, fenced transition outcome, lease takeover, lease remaining, and transition latency. Metrics must not include business identifiers or raw exception data.

## Test Evidence

Required test evidence includes unit-level writer tests, real Mongo lease takeover tests, executor-path stale worker tests, compatibility replay tests, coordinator rollback tests, and architecture guard tests.

## Production/Bank Enablement Conditions

Production or bank operation requires transaction-mode REQUIRED, a startup guard proving REQUIRED mode in bank/prod, lease duration budget review, dashboards for lease remaining, stale rejection, takeover, and transition latency, alerting for stale rejection spikes and recovery write conflicts, an operator runbook for stale lease rejection and recovery-required commands, canary or staging soak, rollback planning, and separate operational release approval. FDP-32 hardens stale-worker behavior but does not enable FDP-29 production behavior.

## Known Limitations

FDP-32 does not prove behavior under process kill, host pause, distributed partition, or external finality gaps. Those remain operational hardening and chaos-test scope.
