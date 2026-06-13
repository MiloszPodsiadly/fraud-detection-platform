# Shadow Performance Generated Runtime Override

Status: FDP-110 local generated-artifact runtime bridge.

FDP-109 generates current-summary.json. FDP-110 mounts generated current-summary.json into local runtime. FDP-110 invokes that local generator from the developer launcher before mounting the artifact. FDP-108 reads current summary. FDP-106 exposes current summary. FDP-107 displays current summary.

## Purpose

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

Base runtime remains fail-closed. No generated summary is mounted by default. No provider configuration is enabled by
default unless explicitly configured.

Expected behavior:

```text
no configured summary -> 404
```

## Demo Runtime

`docker-compose.shadow-performance-demo.yml` uses:

```text
deployment/local-fixtures/shadow-performance/current-summary.demo.json
```

Demo artifact is separate. Demo summary is not FDP-109 generated output. Demo summary is not production current
summary. Demo summary is not promotion readiness. Demo summary is not threshold recommendation. Demo summary is not
production decisioning. Demo summary is not payment authorization. Demo summary is not analyst recommendation logic.

## Generated Runtime

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

The generated artifact is separate. The generated runtime uses
`deployment/local-generated/shadow-performance/current-summary.json` and mounts it read-only to:

```text
/run/shadow-performance/current-summary.json
```

The generated runtime does not use `current-summary.demo.json`. The generated runtime does not generate summary inside Docker Compose. If the generated artifact is still missing after local generation, `make app-up-shadow-performance-generated` fails before `docker compose up` with:

```text
Generated Shadow Performance Summary not found. Run: make shadow-performance-summary
```

## No Compose Runtime Generation

FDP-110 does not run the generator inside Docker Compose. FDP-110 does not add a scheduler. FDP-110 does not add cron.
FDP-110 does not add a Kafka-triggered job. FDP-110 does not generate summary on application startup. The only
automatic generation is the local launcher step before Docker Compose starts.

## Non-Decisioning Boundary

Generated runtime remains local/offline diagnostic. It is not promotion readiness. It is not promotion approval. It is
not threshold recommendation. It is not production decisioning. It is not payment authorization. It is not analyst
recommendation logic.

## Suggested PR Title

FDP-110: Add generated Shadow Performance Summary local runtime override

## Suggested PR Body

```markdown
## Summary

Adds an explicit local Docker Compose override and launcher path that generate the FDP-109 `current-summary.json`
artifact locally before Compose starts, then mount it into the FDP-108 Shadow Performance current summary provider.

This completes the local diagnostic loop:

`make app-up`
-> FDP-109 local generator
-> `deployment/local-generated/shadow-performance/current-summary.json`
-> generated compose override
-> FDP-108 provider
-> FDP-106 endpoint
-> FDP-107 dashboard.

## Included

- `deployment/docker-compose.shadow-performance-generated.yml`
- read-only mount of `deployment/local-generated/shadow-performance/current-summary.json`
- alert-service env configuration for `/run/shadow-performance/current-summary.json`
- `make app-up-shadow-performance-generated`
- one-command `make app-up` / `scripts\app.cmd up` local generation before Compose
- clear failure when generated artifact is missing after local generation
- optional explicit local-loop target if implemented
- docs for base/demo/generated runtime paths
- guard tests for compose, Makefile, docs, and no scope creep

## Out of scope

- generation inside Docker Compose
- generation inside application runtime
- scheduler/cron/background daemon
- Kafka-triggered generation
- production automation
- promotion readiness
- threshold recommendation
- model registry mutation
- online scoring changes
- payment authorization
- analyst recommendation logic
- UI feature expansion
- new API endpoints
```
