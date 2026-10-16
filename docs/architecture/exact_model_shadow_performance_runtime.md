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

The artifact root is provisioned on a Linux filesystem with POSIX ACL support before publication. Publication is atomic and no-replace. Before the selection directory becomes visible, the publisher grants reader UID `10001` read access to the two artifact files and read/traverse access to the exact selection directory. The root grants that UID traverse-only access. Group and other access remain empty, and a filesystem that cannot enforce the exact ACL fails closed. Success is returned only after access checks, artifact files, destination directory, and parent directory have been durably synchronized. An identical retry revalidates the complete artifact set, ACL, and parent-directory durability before returning success; a different payload for the same selector fails closed. One immutable artifact set is permitted for each exact selector; revisions for an unchanged selector are not part of this contract. The committed JSON contract is [exact_model_shadow_performance.schema.json](../schemas/exact_model_shadow_performance.schema.json).

## Runtime

The base application keeps the reader disabled. The repository Makefile is the canonical local entry point:

```bash
make up
make ps
make down
```

`make up` starts the standard hardened local stack and prepares the required runtime fixtures before generating the ordinary Shadow Performance Summary. Tests remain separate under the `check-*` targets. `make down` stops the stack without deleting volumes.

Enable the exact-model reader on Linux or WSL backed by a Linux filesystem with POSIX ACL support:

```bash
make up-exact-model-shadow
```

Fixture bootstrap creates the root without following path-component symlinks, writes the readiness marker atomically, and applies the exact UID `10001` ACL. Git Bash on a Windows filesystem can run the ordinary stack but cannot provision this optional reader boundary. The exact-model overlay declares the pre-provisioned artifact root as a read-only bind with host-path creation disabled. The launcher rejects a missing, symlink-traversing, incorrectly marked, or incorrectly permissioned source before invoking Docker. Set `MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT` when the host artifact root differs from `deployment/local-generated/model-shadow-performance`. The publishing process has separate credentials and runs outside `alert-service`; artifact publication is independent from application startup.

Before starting the service, validate the resolved reader boundary:

```bash
make check-compose
make check-compose-exact-model-shadow
```

These separate checks require the reader to be explicitly enabled, the canonical container path to be selected, the host artifact root to be bind-mounted read-only, and implicit host-path creation to remain disabled. The application preflight and sentinel check are tested separately from a minimal Docker Compose proof that contains no application checker or `label_file`: a missing bind source must be refused, while an existing source is readable and not writable. They are not dependencies of `make up`.

`ModelEvaluationGovernanceValidator` owns the shared recursive evaluation contract and metric arithmetic. `ModelCardValidator` owns Model Card structure, evidence linkage, artifact verification, and usage boundaries. `ExactModelShadowPerformanceValidator` owns its selector, provenance, coverage, diagnostics, and governance boundary while delegating only the embedded evaluation summary. The browser mirrors the same split between `modelEvaluationGovernanceValidation.js`, `modelCardValidation.js`, and `exactModelShadowPerformanceValidation.js`.

## Security And Governance

The endpoint requires `model-shadow-performance:read`. Sensitive-read audit records the selected artifact SHA and a bounded hash of the complete query. The repository mTLS and JWT credentials are local demonstration identities, not production PKI or managed secrets. `coverage.returnedCohortStatus` describes only whether the bounded returned cohort was fully evaluated; `coverage.sourcePopulationStatus` independently reports source truncation and never claims global source completeness. Exact-model evidence identifies one model artifact; Platform Recommendation Shadow Performance describes platform-level recommendation behavior and is not a substitute for exact-model evidence. The response is diagnostic evidence only: it grants no production decision, promotion, threshold, payment, workflow, or registry authority.
