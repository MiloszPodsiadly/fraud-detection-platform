# Model Card governance read API

`GET /api/v1/governance/model-card/current` exposes one explicitly configured Model Card artifact set to callers with `model-card:read`.

The provider reads only `ml_model_card.json`, `ml_model_card.md`, and `manifest.json` from the configured location. It rejects traversal, symlinks, non-canonical filenames, oversized or concurrently changed files, duplicate JSON keys, unsupported fields, and manifest digest or size mismatches. Disabled or unconfigured sources return `404`; configured but missing, corrupt, or inconsistent sources return a detail-safe `503`.

The Python generator is the single writer of this artifact set, and this endpoint is its single application read route. Model Card and Platform Recommendation Evaluation Card share only the neutral `governance_artifact_safety.py` technical policy; neither product imports the other's schema, generator, writer, reader, or business contract. Model Card evaluation evidence comes exclusively from the exact-model evaluation artifact set.

The response is exact-artifact offline evidence. `NOT_EVALUATED_FOR_PROMOTION` and the verified usage boundary are authoritative: the card grants no production decision, model promotion, threshold change, payment authorization, workflow, registry mutation, or scoring authority. It never loads model bytes, recomputes metrics, scans for a latest artifact, or exposes source dataset rows.

The analyst console renders this response inside the existing governance evidence workspace. Its Model Card panel has an independent permission, request lifecycle, and error state, so missing or invalid Model Card evidence cannot hide Shadow Performance or Promotion Review Readiness. The UI preserves artifact identity, provenance, metric availability, coverage, exclusions, warnings, and limitations verbatim; it neither derives replacement metrics nor offers promotion, deployment, threshold, payment, workflow, or automatic-decision controls.
