# Shadow Performance Generated Runtime Override

Status: Current local generated Shadow Performance runtime loop.

The local generated Shadow Performance runtime loop invokes the Python generator before Docker Compose starts, mounts the generated Shadow Performance artifact set into alert-service, lets the artifact provider read it, the authorized API expose it, and the dashboard display it.

The local runtime intentionally combines local generation before Compose, generated runtime mount, and shared global workspace counters as UI context.

Ownership remains split:

- The Python offline-evaluation module owns generation logic.
- The local launcher owns invocation and runtime mounting.
- The alert-service artifact provider owns artifact reading.
- The authorized read API owns exposure.
- The dashboard owns display.

## Purpose

Generation before Compose in a local developer launcher is allowed. Generation inside Docker Compose is forbidden. Generation inside alert-service runtime is forbidden.

The local launcher does not generate a Shadow Performance Summary inside Docker Compose or inside the application runtime. It invokes the Python generator before `docker compose up`, then mounts the generated `current-summary.json` plus sibling `manifest.json` into the local alert-service runtime.

This keeps generation and runtime wiring separate:

```text
make shadow-performance-summary
-> deployment/local-generated/shadow-performance/current-summary.json
-> deployment/local-generated/shadow-performance/manifest.json
-> make app-up-shadow-performance-generated
-> docker-compose.shadow-performance-generated.yml
-> /run/shadow-performance/current-summary.json
-> /run/shadow-performance/manifest.json
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
Demo runtime uses a demo artifact set:

```text
deployment/local-fixtures/shadow-performance/current-summary.json
deployment/local-fixtures/shadow-performance/manifest.json
```

Demo artifact is separate from generated artifact. Demo artifact is for UI smoke/demo only. Demo artifact is not generated current output. Demo artifact is not production current summary. Demo artifact is not promotion readiness. Demo artifact is not threshold recommendation. Demo artifact is not production decisioning. Demo artifact is not payment authorization. Demo artifact is not analyst recommendation logic.

## Generated Runtime

The official local launcher runs Python summary generation before Compose. Generated runtime uses `docker-compose.shadow-performance-generated.yml`.

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

The generated artifact set is separate. Generated runtime mounts `deployment/local-generated/shadow-performance` read-only to:

```text
/run/shadow-performance
```

Generated runtime exposes the manifest-validated summary through the artifact provider, authorized read API, and dashboard.

The generated runtime does not use a non-canonical demo summary filename. The generated runtime does not generate summary inside Docker Compose. If the generated artifact is still missing after local generation, `make app-up-shadow-performance-generated` fails before `docker compose up` with:

```text
Generated Shadow Performance Summary not found. Run: make shadow-performance-summary
```

## No Compose Runtime Generation

The local launcher does not run the generator inside Docker Compose. It does not add a scheduler or cron.
It does not add a Kafka-triggered job or generate summary on application startup. The only
automatic generation is the local launcher step before Docker Compose starts.

Generation happens before Docker Compose starts.
Generation does not happen inside Docker Compose.
Generation does not happen inside alert-service runtime.

## Shared Global Workspace Counters

Shadow Performance workspace may render shared global workspace counters as shell-level UI context.

These counters are not part of ShadowPerformanceSummary.
They are not model evaluation metrics.
They are not used by the Python generator.
They are not read by the artifact provider.
They are not returned by the current summary endpoint.
They are not promotion readiness.
They are not threshold recommendation.
They are not production decisioning.
They are not payment authorization.
They are not analyst recommendation logic.

## Non-Decisioning Boundary

Generated runtime remains local/offline diagnostic. It is not promotion readiness. It is not promotion approval. It is
not threshold recommendation. It is not production decisioning. It is not payment authorization. It is not analyst
recommendation logic.

The local generated runtime is not promotion readiness.
It is not promotion approval.
It is not threshold recommendation.
It is not production decisioning.
It is not payment authorization.
It is not analyst recommendation logic.
It does not mutate model registry.
It does not mutate model artifacts.
It does not change online scoring.
It does not emit Kafka events.
It does not add scheduler/cron/background daemon.

## Suggested PR Title

Add local generated Shadow Performance runtime loop

## Suggested PR Body

```markdown
## Summary

Adds the local generated Shadow Performance runtime loop.

This PR intentionally combines:
1. local summary generation before Docker Compose starts,
2. a generated Shadow Performance Docker Compose override,
3. shared global workspace counters as UI context.

The local loop is:

`make app-up`
-> the Python generator writes `deployment/local-generated/shadow-performance/current-summary.json`
   and `deployment/local-generated/shadow-performance/manifest.json`
-> generated compose override mounts the artifact-set directory read-only into alert-service
-> the artifact provider reads `/run/shadow-performance/current-summary.json`
   and sibling `/run/shadow-performance/manifest.json`
-> the authorized API exposes the current summary
-> the dashboard displays the generated summary.

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

The local generated runtime does not:

- generate inside Docker Compose
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
