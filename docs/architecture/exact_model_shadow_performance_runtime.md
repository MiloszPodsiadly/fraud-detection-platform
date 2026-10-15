# Exact Model Shadow Performance Runtime

## Contract

`GET /api/v1/governance/model-shadow-performance` reads immutable, manifest-verified evidence for one complete selector:

- `modelName`
- `modelVersion`
- `featureContractVersion`
- `modelArtifactSha256`
- optional `fromInclusive`
- optional `toInclusive`

The selector is hashed with the canonical Python/Java selection-key contract. The reader opens only that directory. It never scans for a latest version and never falls back to a neighboring model, SHA, or evaluation window. Missing exact evidence returns `404`; a present but corrupt or inconsistent artifact returns `503`.

The response population is `LABELED_FEEDBACK`. It is not an operational scoring-occurrence population. Exact-artifact p95 latency, fallback rate, and reason-code distribution remain explicitly unavailable because the governed feedback artifact does not contain those observations.

## Publication

The publisher consumes an already validated model-specific evaluation artifact set. It does not recalculate metrics independently:

```bash
cd ml-inference-service
PYTHONPATH=. python -m offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.run_generation \
  --model-evaluation-dir /trusted/model-evaluation \
  --source-dataset /trusted/feedback-dataset.jsonl \
  --artifact-root ../deployment/local-generated/model-shadow-performance \
  --generated-at 2026-10-15T12:00:00Z
```

The artifact root must already exist. Publication is atomic and no-replace. An identical retry is idempotent; a different payload for the same selector fails closed. The committed JSON contract is [exact_model_shadow_performance.schema.json](../schemas/exact_model_shadow_performance.schema.json).

## Runtime

The base application keeps the reader disabled. The explicit overlay mounts a pre-provisioned artifact root read-only and refuses Docker host-path creation:

```bash
docker compose --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  -f deployment/docker-compose.model-shadow-performance-generated.yml \
  up --build -d
```

Set `MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT` when the host artifact root differs from `deployment/local-generated/model-shadow-performance`. The publishing process must have separate credentials and must not run inside `alert-service`.

## Security And Governance

The endpoint requires `model-shadow-performance:read`. Sensitive-read audit records the selected artifact SHA and a bounded hash of the complete query. The response is diagnostic evidence only: it grants no production decision, promotion, threshold, payment, workflow, or registry authority. Existing Model Card and Platform Recommendation Shadow Performance contracts remain separate and unchanged.
