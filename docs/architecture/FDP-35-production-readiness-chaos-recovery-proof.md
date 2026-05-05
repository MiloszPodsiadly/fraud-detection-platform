# FDP-35 Production Readiness Chaos Recovery Proof

FDP-35 is a production-readiness proof branch for regulated mutation execution. It validates operational behavior of the existing regulated mutation model from FDP-29, FDP-31, FDP-32, FDP-33, and FDP-34. It does not add mutation semantics, public statuses, model versions, Kafka/outbox semantics, external finality, distributed locks, distributed ACID, WORM storage, legal notarization, or KMS claims.

## Safety Chain

- FDP-31: claim and replay policy extraction.
- FDP-32: lease-owner fenced command writes.
- FDP-33: bounded lease renewal primitive.
- FDP-34: safe checkpoint adoption.
- FDP-35: production readiness, modeled restart/recovery proof, E2E/API/observability/runbook validation.

## Core Invariants

- Process death must not create false success.
- Recovery state must win over stale response snapshots.
- Checkpoint renewal must not be treated as business progress.
- Long-running PROCESSING must be observable.
- API responses must not expose committed business success for recovery-required commands.
- Operators must have tested, auditable recovery actions.
- Production enablement must be explicit, reviewed, and reversible.

## Readiness Proof Scope

FDP-35 proves readiness through Docker/Testcontainers E2E tests, modeled restart/recovery tests, controller/API recovery behavior tests, dashboard and alert specifications, operator drill runbooks, rollback validation, and CI test discovery.

The restart proof is modeled restart/recovery proof. It seeds durable Mongo states a real process death could leave behind and verifies replay/recovery behavior. It is not a real OS process termination test and must not be described as a real process-kill chaos proof.

FDP-35 provides modeled restart/recovery proof in CI. It recreates durable post-crash states and verifies replay/recovery/API behavior against the same Mongo-backed command state. It does not claim real OS/JVM/container kill chaos proof unless an explicit kill/restart test is added.

True OS/JVM/container process termination chaos remains future scope unless explicitly implemented and run in CI.

## Non Goals

- No new mutation types.
- No automatic FDP-29 production enablement.
- No new public API statuses.
- No Kafka/outbox semantic changes.
- No external finality claim.
- No distributed lock claim.
- No distributed ACID claim.
- No real OS/JVM/container kill proof unless a dedicated explicit chaos job is implemented and green.
- No state machine rewrite.
- No WORM, legal notarization, or KMS claim.

## Reviewer Checklist

- Docker/Testcontainers production-readiness tests are green.
- Controller/API recovery behavior tests are green.
- Modeled restart/recovery proof exists and is labeled as modeled.
- Recovery runbook includes filled sample output.
- Dashboard and alert threshold specs exist.
- Rollback plan exists and avoids manual DB edits.
- FDP-29/FDP-32/FDP-33/FDP-34 regression matrix links prior invariants to tests.
- Production enablement config remains unchanged.
