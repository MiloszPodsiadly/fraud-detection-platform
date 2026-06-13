# Shadow Performance Summary Current Provider

FDP-108 provides the current summary source for FDP-106.

The provider is an artifact-backed Current Provider Foundation for:

`GET /api/v1/governance/shadow-performance/summary/current`

It reads one explicitly configured current `ShadowPerformanceSummary` JSON artifact, validates it with the existing FDP-105/FDP-106 validator, and returns it through the existing authorized read path.

## Configuration

Configuration is server-side only:

- `shadow-performance.summary.current.enabled`
- `shadow-performance.summary.current.base-dir`
- `shadow-performance.summary.current.path`
- `shadow-performance.summary.current.max-size-bytes`

The default is safe. When the provider is disabled or no path is configured, there is no current summary and FDP-106 returns 404.

There is no default sample path, classpath fixture, hardcoded summary, directory scan, wildcard lookup, latest-by-time lookup, history lookup, or static provider fallback.

## Source Boundary

The only allowed source is the configured current Shadow Performance Summary v1 JSON artifact under the configured safe base directory. The default base directory is `/run/shadow-performance`. The source is bounded to the configured safe directory and does not allow symlink artifacts.

The configured path is normalized and must resolve under the safe base directory. The provider rejects path traversal, paths outside the base directory, symlink artifacts, directories, non-regular files, unsupported file extensions, and artifacts larger than the configured maximum size.

The provider does not read raw FDP-102/FDP-103/FDP-104 artifacts, raw Model Cards, FDP-102 JSONL exports, model registry data, model artifact binaries, scoring databases, Kafka topics, transaction stores, alert stores, fraud-case stores, or payment authorization services.

The provider does not expose raw artifacts, configured filesystem paths, parser exceptions, validation exceptions, or stack traces through the API.

## Primitive Defaulting Boundary

The artifact provider fails closed on missing or null primitive JSON fields. It configures Jackson to reject missing creator properties, null creator properties, null primitives, scalar coercion, and unknown properties.

This prevents malformed artifacts from silently defaulting metrics or governance fields to 0, 0.0, or false.

Missing/null primitive fields are treated as invalid/unavailable configured source and result in 503 through FDP-106.

- Missing primitive metric field -> 503.
- Null primitive metric field -> 503.
- Missing/null governance boolean -> 503.
- Missing/null evaluation population count -> 503.
- Missing/null disagreement count -> 503.
- No silent primitive defaults.
- No zero substitution.
- No false substitution.
- No partial summary.

## Failure Semantics

- Disabled provider or no configured path returns 404.
- Configured missing artifact returns 503.
- Unavailable or invalid configured source returns 503.
- Malformed JSON returns 503.
- Valid JSON that fails `ShadowPerformanceSummaryValidator` returns 503.

No fake, sample, stale, fallback, or zero metrics are returned. Invalid configured data is never converted into a missing summary and never becomes a partial success.

## Local Docker Runtime

The base runtime is fail-closed by default. Standard local startup does not mount a current summary artifact and does not enable the provider:

- `SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED=false`
- `SHADOW_PERFORMANCE_SUMMARY_CURRENT_BASE_DIR=/run/shadow-performance`
- `SHADOW_PERFORMANCE_SUMMARY_CURRENT_PATH=`
- `SHADOW_PERFORMANCE_SUMMARY_CURRENT_MAX_SIZE_BYTES=1048576`

The official full local launchers generate the local summary first and include the explicit generated override, not
the demo override, so the Shadow Performance dashboard uses the FDP-109 generated artifact:

```powershell
.\scripts\app.cmd up
```

On macOS or Linux:

```bash
make app-up
```

For manual full local startup, include the explicit generated override:

```bash
docker compose --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  -f deployment/docker-compose.dev.yml \
  -f deployment/docker-compose.oidc.yml \
  -f deployment/docker-compose.service-identity-mtls.yml \
  -f deployment/docker-compose.trust-authority-jwt.yml \
  -f deployment/docker-compose.hardened.yml \
  -f deployment/docker-compose.shadow-performance-generated.yml \
  up --build -d
```

The generated override mounts `deployment/local-generated/shadow-performance/current-summary.json` read-only as `/run/shadow-performance/current-summary.json`. The generated runtime does not use `current-summary.demo.json` and does not generate a summary inside Docker Compose.

The separate demo override mounts `deployment/local-fixtures/shadow-performance/current-summary.demo.json` read-only as `/run/shadow-performance/current-summary.demo.json`. Demo fixture metrics are not production current summary, not promotion readiness, not threshold recommendation, not production decisioning approval, not payment authorization, and not analyst recommendation logic. The demo fixture metrics are local demonstration data only; demo fixture metrics are not production current summary.

If the base Compose file is run without a configured current summary source, the endpoint returns 404. If a different artifact is configured, its path must point to an existing valid current `ShadowPerformanceSummary v1` JSON artifact mounted inside the `alert-service` container under the configured safe base directory. If the provider is disabled or has no path, 404 is expected. If the configured file is missing, unreadable, malformed, invalid, too large, a symlink, a directory, outside the safe base directory, or not `.json`, the endpoint returns 503 with a safe generic response.

## Non-Goals

The provider is read-only. It does not compute metrics, does not recompute shadow performance, repair invalid metrics, coerce invalid fields, enrich summaries, generate Model Cards, write files, emit Kafka messages, mutate model registry state, mutate model artifacts, mutate alert severity, mutate fraud-case status, trigger retraining, change scoring, authorize payments, or create analyst recommendations.

Static/sample summary data is test/demo fixture only. Production/main provider source is artifact-backed or empty fail-closed. Default runtime never uses hardcoded summary.

FDP-108 provides a validated current ShadowPerformanceSummary. It does not create model readiness, promotion approval, threshold recommendation, production decisioning approval, payment authorization, or analyst recommendation logic.
