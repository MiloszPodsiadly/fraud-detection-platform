# Model Card governance read API

`GET /api/v1/governance/model-card/current` exposes one explicitly configured Model Card artifact set to callers with `model-card:read`.

The provider reads only `ml_model_card.json`, `ml_model_card.md`, and `manifest.json` from the configured location. It rejects traversal, symlinks, non-canonical filenames, oversized or concurrently changed files, duplicate JSON keys, unsupported fields, and manifest digest or size mismatches. Disabled or unconfigured sources return `404`; configured but missing, corrupt, or inconsistent sources return a detail-safe `503`.

The Python generator is the single writer of this artifact set, and this endpoint is its single application read route. Model Card and Platform Recommendation Evaluation Card share only the neutral `governance_artifact_safety.py` technical policy; neither product imports the other's schema, generator, writer, reader, or business contract. Model Card evaluation evidence comes exclusively from the exact-model evaluation artifact set.

## Immutable publication and filesystem boundary

Each generated Model Card is published to a new version-specific directory that must not already exist. The writer creates a uniquely owned staging directory beside the destination, writes canonical JSON and Markdown, writes the manifest last, validates the complete staged set with the canonical Python reader, and then performs one same-filesystem atomic no-replace directory rename. An exclusive publication lock prevents cooperating publishers from targeting the same version concurrently. The supported Linux writer runtime uses `renameat2(RENAME_NOREPLACE)`; Windows test and development runtimes use the native no-replace behavior of `rename`. Existing empty and nonempty destinations are both rejected, including a destination created independently after staging validation. A runtime or filesystem that cannot provide the required primitive fails publication closed. Switching versions means changing the one configured backend path during a controlled deployment or restart. Symlink pointers are not supported.

This protocol gives readers complete-set-or-unavailable namespace visibility on supported local filesystems that implement the selected no-replace primitive under the deployment controls below. It is not a universal distributed-filesystem, network-filesystem, or object-store atomicity claim. The writer does not issue file or directory `fsync`, so successful return is not a claim of crash-durable media persistence. A process crash can leave its uniquely named staging directory or publication lock for operator inspection and cleanup; another publisher never deletes staging it does not own.

Interrupted publication is recovered only through the [Model Card publication recovery runbook](../runbooks/model_card_publication_recovery.md). Lock metadata supports correlation with workload and publisher logs but is never automatic deletion authority; age and PID alone are insufficient.

The artifact parent directory must have trusted ownership, admit no untrusted writers, and be mounted read-only into the alert service. Publication must happen in a controlled writer environment on the same filesystem as the destination. The Java reader rejects symlinks, traversal, unexpected directory entries, non-regular or oversized files, and observable attribute/content changes across bounded reads. These checks reduce replacement races but do not make an untrusted writable parent universally race-free; filesystem ownership and controlled publication remain required controls.

The response is exact-artifact offline evidence. `NOT_EVALUATED_FOR_PROMOTION` and the verified usage boundary are authoritative: the card grants no production decision, model promotion, threshold change, payment authorization, workflow, registry mutation, or scoring authority. It never loads model bytes, recomputes metrics, scans for a latest artifact, or exposes source dataset rows.

## Model metadata provenance decisions

The current exact-artifact contract identifies its subject only by model name, model version, feature-contract version, and artifact SHA-256. The additional fields below are not required, so adding them would be a schema, Java API, OpenAPI, and UI expansion rather than completion of the current contract.

| Field | Authoritative source | Exact SHA bound | Current contract required | Decision | Reason |
| --- | --- | --- | --- | --- | --- |
| `modelFamily` | Validated top-level field in the immutable model artifact | Yes | No | Do not add | The loader validates it from the same exact bytes, but the closed Model Card subject and model-evaluation identity do not consume it. It is never inferred from the model name. |
| `trainingMode` | Validated top-level and nested fields in the immutable model artifact | Yes | No | Do not add | The loader requires both artifact values to agree, but the current card does not expose it. It is never inferred from filenames. |
| `referenceQuality` | Runtime governance reference-profile snapshot | No | No | Do not add | It is not stored in the exact model artifact or exact-model evaluation identity. Current runtime or registry state cannot truthfully backfill a historical card, and dataset verification levels are not reference quality. |

No placeholder or `UNKNOWN` representation is added, and the card's non-decisioning status remains unchanged.

The verified usage boundary is a closed static governance policy. Intended use is limited to exact-model offline evaluation, Rules-vs-ML diagnostic comparison, and shadow monitoring. `notIntendedUse` explicitly excludes primary production fraud decisioning, promotion approval, threshold changes, payment authorization, automatic approve/decline/block actions, and workflow or analyst recommendations. `useLimitations` states that evidence is bounded to returned analyst-feedback records, does not establish global dataset completeness, calibration, or production readiness, and must be interpreted together with its source-verification level. These lists are allowlisted contract values, not claims inferred from model bytes.

Canonical Markdown and the analyst panel present the same preserved evaluation values: source quality counts, population and class balance, confusion counts, scalar metric availability and values, requested and actual K ranking values, disagreement categories, warnings, limitations, and usage policy. They never calculate substitute metrics. An unavailable value displays its reason rather than numeric zero. Population coverage is `NO_DATA`, `PARTIAL`, or `COMPLETE_EVALUATED_POPULATION`; the last state covers only a nonempty returned population. `SOURCE_TRUNCATED` is an independent source warning and never means the complete source dataset was evaluated.

The analyst console renders this response inside the existing governance evidence workspace. Its Model Card panel has an independent permission, request lifecycle, and error state, so missing or invalid Model Card evidence cannot hide Shadow Performance or Promotion Review Readiness. The UI preserves artifact identity, provenance, metric availability, coverage, exclusions, warnings, and limitations verbatim; it neither derives replacement metrics nor offers promotion, deployment, threshold, payment, workflow, or automatic-decision controls.
