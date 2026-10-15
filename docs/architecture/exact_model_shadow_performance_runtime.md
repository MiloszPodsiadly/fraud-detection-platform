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

The embedded `evaluationSummary` is not an opaque object. Python, Java, and the analyst UI apply the same authoritative model-evaluation semantics recursively: exact nested field sets, finite metric values, confusion-matrix and population arithmetic, ranking denominators, disagreement categories, warnings, limitations, identity, and time-window consistency. A valid manifest hash cannot make semantically invalid or unexpected nested evidence acceptable, and raw feedback records are never part of this read contract.

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

The artifact root must already exist. Publication is atomic and no-replace. Success is returned only after the artifact files, destination directory, and parent directory have been durably synchronized. An identical retry revalidates the complete artifact set and reconfirms parent-directory durability before returning success; a different payload for the same selector fails closed. Directory durability is not silently weakened on unsupported filesystems. One immutable artifact set is permitted for each exact selector; revisions for an unchanged selector are not part of this contract. The committed JSON contract is [exact_model_shadow_performance.schema.json](../schemas/exact_model_shadow_performance.schema.json).

## Runtime

The base application keeps the reader disabled. On Linux, WSL, or Git Bash, the repository Makefile is the canonical local entry point:

```bash
make up
make ps
make down
```

`make up` starts the standard hardened local stack and prepares the required runtime fixtures before generating the ordinary Shadow Performance Summary. Tests remain separate under the `check-*` targets. `make down` stops the stack without deleting volumes.

Enable the exact-model reader after provisioning its artifact root:

```bash
mkdir -p deployment/local-generated/model-shadow-performance
make up-exact-model-shadow
```

The exact-model overlay mounts the pre-provisioned artifact root read-only and refuses Docker host-path creation. Set `MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT` when the host artifact root differs from `deployment/local-generated/model-shadow-performance`. The publishing process has separate credentials and runs outside `alert-service`; artifact publication is independent from application startup. The Makefile execution environments are Linux, WSL, and Git Bash.

Before starting the service, validate the resolved reader boundary:

```bash
make check-compose
make check-compose-exact-model-shadow
```

These separate checks require the reader to be explicitly enabled, the canonical container path to be selected, the host artifact root to be bind-mounted read-only, and implicit host-path creation to remain disabled. They also exercise Docker's refusal to create a missing source and its enforcement of the read-only mount. They are not dependencies of `make up`.

The recursive evaluation validator is currently implemented by `ModelCardValidator` and reused by the exact-model validator, so there is one semantic implementation. Extracting that substantial implementation into a neutrally named component is deferred to avoid creating a second validator or changing the active contract during this focused hardening work.

## Security And Governance

The endpoint requires `model-shadow-performance:read`. Sensitive-read audit records the selected artifact SHA and a bounded hash of the complete query. `coverage.returnedCohortStatus` describes only whether the bounded returned cohort was fully evaluated; `coverage.sourcePopulationStatus` independently reports source truncation and never claims global source completeness. The response is diagnostic evidence only: it grants no production decision, promotion, threshold, payment, workflow, or registry authority. Existing Model Card and Platform Recommendation Shadow Performance contracts remain separate and unchanged.
