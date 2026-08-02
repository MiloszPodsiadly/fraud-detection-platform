# Shadow Performance Summary Current Provider

FDP-108 provides the current summary source for FDP-106.

The provider is an artifact-backed Current Provider Foundation for:

`GET /api/v2/governance/shadow-performance/summary/current`

It reads one explicitly configured current `ShadowPerformanceSummary` V2 JSON artifact, validates the required sibling `manifest.json`, validates the summary with the existing FDP-105/FDP-106 validator, and returns it through the existing authorized read path.

## Configuration

Configuration is server-side only:

- `shadow-performance.summary.current.enabled`
- `shadow-performance.summary.current.base-dir`
- `shadow-performance.summary.current.path`
- `shadow-performance.summary.current.max-size-bytes`

The default is safe. When the provider is disabled or no path is configured, there is no current summary and FDP-106 returns 404.

There is no default sample path, classpath fixture, hardcoded summary, directory scan, wildcard lookup, latest-by-time lookup, history lookup, or static provider fallback.

## Source Boundary

The only allowed source is the configured current Shadow Performance Summary V2 artifact set under the configured safe base directory. The default base directory is `/run/shadow-performance`. The source is bounded to the configured safe directory and does not allow symlink artifacts.

The configured summary path must point to the canonical `current-summary.json` artifact. The provider resolves the deterministic sibling `manifest.json` in the same directory and requires both files to be regular, non-symlink `.json` files under the safe base directory.

The configured path is normalized and must resolve under the safe base directory. The provider rejects path traversal, paths outside the base directory, symlink artifacts, directories, non-regular files, unsupported file extensions, and artifacts larger than the configured maximum size.

The provider does not read raw dataset exports, raw evaluation reports, raw Platform Recommendation Evaluation Cards, model registry data, model artifact binaries, scoring databases, Kafka topics, transaction stores, alert stores, fraud-case stores, or payment authorization services.

The provider does not expose raw artifacts, configured filesystem paths, parser exceptions, validation exceptions, or stack traces through the API.

## Primitive Defaulting Boundary

The artifact provider fails closed on missing or null primitive JSON fields. It configures Jackson to reject missing creator properties, null creator properties, null primitives, scalar coercion, and unknown properties.

This prevents malformed artifacts from silently defaulting metrics or governance fields to 0, 0.0, or false.

Missing/null primitive fields are treated as invalid/unavailable configured source and result in 503 through FDP-106.

- Missing primitive metric field -> 503.
- Null primitive metric field -> 503.
- Missing/null governance boolean -> 503.
- Missing/null evaluation population count -> 503.
- No silent primitive defaults.
- No zero substitution.
- No false substitution.
- No partial summary.

## Manifest Boundary

The current summary is not consumed as a standalone file. The configured `current-summary.json` must have a sibling `manifest.json` with:

- `reportType = SHADOW_PERFORMANCE_ARTIFACT_SET_V1`.
- `artifactSetVersion = shadow-performance-artifact-set-v1`.
- `generatedAt` exactly equal to the summary `generatedAt`.
- exactly one file entry for `current-summary.json`.
- lowercase SHA-256 for the exact summary bytes consumed by the provider.
- `sizeBytes` equal to the exact byte length consumed by the provider.

The manifest is validated before the summary can be returned. Missing, malformed, unsupported, oversized, symlinked, or semantically invalid manifests result in 503. A hash mismatch, size mismatch, timestamp mismatch, wrong filename, extra file entry, wrong report type, or wrong artifact-set version also results in 503.

The SHA-256 value is a local integrity fingerprint for the deployment-controlled artifact set. It is not a digital signature, producer identity, external attestation, or protection against a privileged writer replacing both `current-summary.json` and `manifest.json`.

Published summary and manifest timestamps must be RFC3339 UTC `Z` strings with optional 1-9 digit fractional seconds.
Equivalent offset encodings such as `+00:00` are rejected instead of normalized while reading a configured artifact set.

## Failure Semantics

- Disabled provider or no configured path returns 404.
- Configured missing artifact returns 503.
- Missing required sibling `manifest.json` returns 503.
- Unavailable or invalid configured source returns 503.
- Malformed JSON returns 503.
- Invalid manifest, manifest-summary hash mismatch, or manifest-summary size mismatch returns 503.
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

The generated override must make the generated Shadow Performance artifact set available to `alert-service` as `/run/shadow-performance/current-summary.json` plus sibling `/run/shadow-performance/manifest.json`. The generated runtime does not use a non-canonical demo summary filename and does not generate a summary inside Docker Compose.

Any separate demo data must still satisfy the same V2 artifact-set contract: canonical `current-summary.json` plus sibling `manifest.json`. Demo fixture metrics are not production current summary, not promotion readiness, not threshold recommendation, not production decisioning approval, not payment authorization, and not analyst recommendation logic. The demo fixture metrics are local demonstration data only; demo fixture metrics are not production current summary.

If the base Compose file is run without a configured current summary source, the endpoint returns 404. If a different artifact set is configured, its path must point to an existing valid current `ShadowPerformanceSummary` V2 `current-summary.json` artifact mounted inside the `alert-service` container under the configured safe base directory with sibling `manifest.json`. If the provider is disabled or has no path, 404 is expected. If the configured summary or required manifest is missing, unreadable, malformed, invalid, too large, a symlink, a directory, outside the safe base directory, not `.json`, or inconsistent by SHA-256/`sizeBytes`, the endpoint returns 503 with a safe generic response.

## Non-Goals

The provider is read-only. It does not compute metrics, does not recompute shadow performance, repair invalid metrics, coerce invalid fields, enrich summaries, generate Platform Recommendation Evaluation Cards, write files, emit Kafka messages, mutate model registry state, mutate model artifacts, mutate alert severity, mutate fraud-case status, trigger retraining, change scoring, authorize payments, or create analyst recommendations.

Static/sample summary data is test/demo fixture only. Production/main provider source is artifact-backed or empty fail-closed. Default runtime never uses hardcoded summary.

FDP-108 provides a validated current ShadowPerformanceSummary. It does not create model readiness, promotion approval, threshold recommendation, production decisioning approval, payment authorization, or analyst recommendation logic.
