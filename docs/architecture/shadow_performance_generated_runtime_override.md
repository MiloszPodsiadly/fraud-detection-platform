# Shadow Performance Generated Runtime Override

Status: FDP-110 local generated Shadow Performance runtime loop.

FDP-110 completes the local generated Shadow Performance runtime loop. It invokes the FDP-109 local generator before Docker Compose starts, mounts the generated current-summary.json artifact into alert-service, lets FDP-108 read it, FDP-106 expose it, and FDP-107 display it.

FDP-110 intentionally combines local generation before Compose, generated runtime mount, and shared global workspace counters as UI context.

Ownership remains split:

- FDP-109 owns generation logic.
- FDP-110 owns local launcher wiring and runtime mounting.
- FDP-108 owns artifact reading.
- FDP-106 owns the authorized read API.
- FDP-107 owns dashboard display.

## Purpose

Generation before Compose in a local developer launcher is allowed. Generation inside Docker Compose is forbidden. Generation inside alert-service runtime is forbidden.

FDP-110 does not generate a Shadow Performance Summary inside Docker Compose or inside the application runtime. The local developer launcher invokes the FDP-109 generator before `docker compose up`, then mounts the generated current-summary.json artifact into the local alert-service runtime.

This keeps generation and runtime wiring separate:

```text
make shadow-performance-summary
-> deployment/local-generated/shadow-performance/current-summary.json
-> make app-up-shadow-performance-generated
-> docker-compose.shadow-performance-generated.yml
-> /run/shadow-performance/current-summary.json
-> FDP-108 provider
-> FDP-106 API
-> FDP-107 dashboard
```

## Base Runtime

Base runtime is fail-closed. No generated summary is mounted by default in base compose. No provider source is enabled unless explicitly configured.

Expected behavior:

```text
no configured summary -> 404
```

## Demo Runtime

Demo runtime uses `docker-compose.shadow-performance-demo.yml`.
Demo runtime uses:

```text
deployment/local-fixtures/shadow-performance/current-summary.demo.json
```

Demo artifact is separate from generated artifact. Demo artifact is for UI smoke/demo only. Demo artifact is not FDP-109 generated output. Demo artifact is not production current summary. Demo artifact is not promotion readiness. Demo artifact is not threshold recommendation. Demo artifact is not production decisioning. Demo artifact is not payment authorization. Demo artifact is not analyst recommendation logic.

## Generated Runtime

Official local launcher runs FDP-109 generation before Compose. Generated runtime uses `docker-compose.shadow-performance-generated.yml`.

Generated runtime uses:

```bash
make app-up-shadow-performance-generated
```

The official full local launchers also generate the artifact locally before using the generated override:

```bash
make app-up
```

On Windows:

```powershell
.\scripts\app.cmd up
```

The optional explicit local loop remains:

```bash
make shadow-performance-local-loop
```

The generated artifact is separate. Generated runtime mounts `deployment/local-generated/shadow-performance/current-summary.json` read-only to:

```text
/run/shadow-performance/current-summary.json
```

Generated runtime exposes the summary through FDP-108/FDP-106/FDP-107.

The generated runtime does not use `current-summary.demo.json`. The generated runtime does not generate summary inside Docker Compose. If the generated artifact is still missing after local generation, `make app-up-shadow-performance-generated` fails before `docker compose up` with:

```text
Generated Shadow Performance Summary not found. Run: make shadow-performance-summary
```

## No Compose Runtime Generation

FDP-110 does not run the generator inside Docker Compose. FDP-110 does not add a scheduler. FDP-110 does not add cron.
FDP-110 does not add a Kafka-triggered job. FDP-110 does not generate summary on application startup. The only
automatic generation is the local launcher step before Docker Compose starts.

Generation happens before Docker Compose starts.
Generation does not happen inside Docker Compose.
Generation does not happen inside alert-service runtime.

## Shared Global Workspace Counters

Shadow Performance workspace may render shared global workspace counters as shell-level UI context.

These counters are not part of ShadowPerformanceSummary.
They are not model evaluation metrics.
They are not used by FDP-109 generation.
They are not read by FDP-108 provider.
They are not returned by FDP-106 current summary endpoint.
They are not promotion readiness.
They are not threshold recommendation.
They are not production decisioning.
They are not payment authorization.
They are not analyst recommendation logic.

## Non-Decisioning Boundary

Generated runtime remains local/offline diagnostic. It is not promotion readiness. It is not promotion approval. It is
not threshold recommendation. It is not production decisioning. It is not payment authorization. It is not analyst
recommendation logic.

FDP-110 is not promotion readiness.
FDP-110 is not promotion approval.
FDP-110 is not threshold recommendation.
FDP-110 is not production decisioning.
FDP-110 is not payment authorization.
FDP-110 is not analyst recommendation logic.
FDP-110 does not mutate model registry.
FDP-110 does not mutate model artifacts.
FDP-110 does not change online scoring.
FDP-110 does not emit Kafka events.
FDP-110 does not add scheduler/cron/background daemon.

## Suggested PR Title

FDP-110: Add local generated Shadow Performance runtime loop

## Suggested PR Body

```markdown
## Summary

Adds the FDP-110 local generated Shadow Performance runtime loop.

This PR intentionally combines:
1. local FDP-109 summary generation before Docker Compose starts,
2. a generated Shadow Performance Docker Compose override,
3. shared global workspace counters as UI context.

The local loop is:

`make app-up`
-> FDP-109 generates `deployment/local-generated/shadow-performance/current-summary.json`
-> generated compose override mounts it read-only into alert-service
-> FDP-108 reads `/run/shadow-performance/current-summary.json`
-> FDP-106 exposes the current summary
-> FDP-107 displays the generated summary.

## Included

- `deployment/docker-compose.shadow-performance-generated.yml`
- read-only generated artifact mount
- generated compose separated from demo compose
- Makefile generated local launcher path
- Windows generated local launcher path
- clear failure when generated artifact is missing after local generation
- docs for base/demo/generated runtime paths
- global workspace counters restored in Shadow Performance shell context
- guard tests for compose, Makefile, docs, UI counters, and no scope creep
- CI compose config validation for generated runtime

## Important boundaries

Generation happens before Docker Compose starts in the local developer launcher.

FDP-110 does not:

- generation inside Docker Compose
- generate inside alert-service runtime
- add scheduler/cron/background daemon
- add Kafka-triggered generation
- add production automation
- add promotion readiness
- add threshold recommendation
- add production decisioning
- add payment authorization
- add analyst recommendation logic
- mutate model registry
- change online scoring
- add new API endpoints
- expose raw FDP-102/FDP-103/FDP-104 artifacts

## Global counters

Global workspace counters are UI context only.

They are not part of `ShadowPerformanceSummary`, not model evaluation metrics, not readiness, not threshold recommendation, not production decisioning, not payment authorization, and not analyst recommendation logic.
```
