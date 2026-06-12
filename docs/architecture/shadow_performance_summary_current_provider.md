# Shadow Performance Summary Current Provider

FDP-108 provides the current summary source for FDP-106.

The provider is an artifact-backed Current Provider Foundation for:

`GET /api/v1/governance/shadow-performance/summary/current`

It reads one explicitly configured current `ShadowPerformanceSummary` JSON artifact, validates it with the existing FDP-105/FDP-106 validator, and returns it through the existing authorized read path.

## Configuration

Configuration is server-side only:

- `shadow-performance.summary.current.enabled`
- `shadow-performance.summary.current.path`
- `shadow-performance.summary.current.max-size-bytes`

The default is safe. When the provider is disabled or no path is configured, there is no current summary and FDP-106 returns 404.

There is no default sample path, classpath fixture, hardcoded summary, directory scan, wildcard lookup, latest-by-time lookup, history lookup, or static provider fallback.

## Source Boundary

The only allowed source is the configured current Shadow Performance Summary v1 JSON artifact.

The provider does not read raw FDP-102/FDP-103/FDP-104 artifacts, raw Model Cards, FDP-102 JSONL exports, model registry data, model artifact binaries, scoring databases, Kafka topics, transaction stores, alert stores, fraud-case stores, or payment authorization services.

The provider does not expose raw artifacts, configured filesystem paths, parser exceptions, validation exceptions, or stack traces through the API.

## Failure Semantics

- Missing or unconfigured current summary returns 404.
- Missing configured artifact returns 404.
- Unavailable or invalid configured source returns 503.
- Malformed JSON returns 503.
- Valid JSON that fails `ShadowPerformanceSummaryValidator` returns 503.

No fake, sample, stale, fallback, or zero metrics are returned. Invalid configured data is never converted into a missing summary and never becomes a partial success.

## Local Docker Runtime

The local Docker Compose runtime mounts the repository-owned validated `ShadowPerformanceSummary v1` local evaluation artifact read-only at `/run/shadow-performance/current-summary.json` and enables the provider for `alert-service`.

- `SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED=true`
- `SHADOW_PERFORMANCE_SUMMARY_CURRENT_PATH=/run/shadow-performance/current-summary.json`
- `SHADOW_PERFORMANCE_SUMMARY_CURRENT_MAX_SIZE_BYTES=1048576`

The standard local launchers therefore show dashboard metrics after startup:

```powershell
.\scripts\app.cmd up
```

On macOS or Linux:

```bash
make app-up
```

Set `SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED=false` to exercise the fail-closed 404 state. If a different artifact is configured, its path must point to an existing valid current `ShadowPerformanceSummary v1` JSON artifact mounted inside the `alert-service` container. If the provider is disabled or has no path, 404 is expected. If the mounted file is unreadable, malformed, invalid, too large, a directory, or not `.json`, the endpoint returns 503 with a safe generic response.

## Non-Goals

The provider is read-only. It does not compute metrics, does not recompute shadow performance, repair invalid metrics, coerce invalid fields, enrich summaries, generate Model Cards, write files, emit Kafka messages, mutate model registry state, mutate model artifacts, mutate alert severity, mutate fraud-case status, trigger retraining, change scoring, authorize payments, or create analyst recommendations.

FDP-108 provides a validated current ShadowPerformanceSummary. It does not create model readiness, promotion approval, threshold recommendation, production decisioning approval, payment authorization, or analyst recommendation logic.
