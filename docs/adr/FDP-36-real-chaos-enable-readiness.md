# FDP-36 Real Chaos Enablement Readiness ADR

FDP-36 provides real Docker/container kill-restart proof for selected regulated mutation crash windows. It does not change regulated mutation semantics.

## Scope

FDP-36 is a proof and operations branch. It verifies that existing regulated mutation safety mechanisms survive selected real Docker/container kill-restart windows with durable Mongo state preserved across restart.

FDP-35 provides modeled restart/recovery proof in CI. FDP-36 provides real container kill/restart proof for selected windows.

## Boundaries

FDP-36 changes are limited to:

- test harness code under `alert-service/src/test`
- real-chaos and docker-chaos tagged tests
- CI orchestration
- architecture guards
- documentation, proof matrix, and runbooks

Runtime code remains clean. Executors, coordinators, domain handlers, transaction boundaries, public statuses, Kafka/outbox semantics, and FDP-29 production defaults must not change.

## Non-Goals

- no production enablement
- no new mutation type
- no new state machine
- no new public API status
- no external finality
- no distributed ACID
- no distributed lock
- no exactly-once Kafka guarantee
- no legal/WORM/notarization guarantee
- no KMS/HSM proof
- no FDP-29 production-mode enablement
- no runtime chaos hooks in executors, coordinators, or domain services

## Required Evidence

FDP-36 merge requires:

- real Docker/container chaos test green
- Mongo durable state survives restart
- recovery or inspection API is explicit after restart
- no false committed success
- no false finalized confirmed
- no duplicate mutation
- no duplicate outbox
- no duplicate SUCCESS audit
- no fake external confirmation
- enablement checklist produced, without enabling anything

## Allowed Claim

FDP-36 may claim real Docker/container kill-restart proof for selected regulated mutation windows covered by the proof matrix.

## Forbidden Claim

FDP-36 must not claim production enabled, production certified, external finality, distributed ACID, distributed lock, exactly-once Kafka, WORM/legal notarization, runtime chaos hook requirement, automatic bank enablement, or FDP-29 auto-enabled behavior.

