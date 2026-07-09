# Model Card v1

Status: offline governance artifact.

## Scope

Model Card v1 is an offline governance artifact.

The repository has two separated model card paths:

- The existing FDP-103/FDP-102 path uses `offline_evaluation/model_card_schema.py`,
  `offline_evaluation/model_card_generator.py`, and `offline_evaluation/model_card_writer.py`.
- The FDP-123/FDP-124 path uses `offline_evaluation/fdp123/model_card/` and is scoped only to FDP-124
  aggregate offline evaluation artifacts.

The FDP-123/FDP-124 model card path validates the FDP-124 `manifest.json` before trusting
`evaluation_summary.json`. It does not read `disagreement_report.jsonl` in v1. It does not read the raw FDP-123
dataset. It is aggregate-only and uses explicit local model metadata supplied by the caller.

This model card support is governance documentation only. It is not model promotion, not production approval,
not threshold recommendation, not payment authorization, not workflow or case automation, and not legal ground truth.
It does not expose public API, admin API, UI, scheduler, database writes, Kafka events, network calls, or external
publishing. External publishing requires a separate security and governance review.

The existing FDP-103/FDP-102 Model Card v1 path does not promote models, does not recommend thresholds,
does not approve, decline, or block transactions, and does not authorize payments. Dashboards and promotion workflows
are future scopes and require separate architecture review.

Dashboards and promotion workflows are future scopes.

## Inputs

The FDP-123/FDP-124 model card generator accepts only:

- FDP-124 `manifest.json`,
- FDP-124 `evaluation_summary.json`,
- explicit local static model metadata.

The generator fails closed if the manifest is missing, does not list `evaluation_summary.json`, has unsupported report
identity, or has hash or size mismatches. It also fails closed when `evaluation_summary.json` has unsupported report
identity, missing generated time, missing dataset summary, missing class balance, missing alert-recommended confusion
matrix, invalid metric objects, forbidden fields, or raw identifiers.

Required model metadata includes model name, model version, model family, training mode, feature contract version,
reference quality, intended use, not intended use, allowed usage modes, limitations, and governance boundary. Identity
values must be explicit safe identifiers, not URLs, file paths, bucket locations, registry locations, tokens, secrets,
or guessed values such as `unknown` or `v1`.

For the existing FDP-103/FDP-102 path, Model Card v1 validates FDP-103 report identity, validates metric basis,
validates dataset time basis, validates deduplication policy, validates metric numeric types and ranges, and validates
disagreementSummary with allowlisted keys. Model identity fields are safe identifiers, not URLs, paths, bucket URIs,
registry endpoints, artifact locations, or secrets. intendedUse is allowlisted, and required notIntendedUse non-goals
cannot be omitted.

Model Card v1 validates FDP-103 report identity.
Model Card v1 validates metric basis.
Model Card v1 validates dataset time basis.
Model Card v1 validates deduplication policy.
Model Card v1 validates metric numeric types and ranges.
Model Card v1 validates disagreementSummary with allowlisted keys.
Model identity fields are safe identifiers, not URLs, paths, bucket URIs, registry endpoints, artifact locations, or secrets.
intendedUse is allowlisted.
required notIntendedUse non-goals cannot be omitted.

## Semantics

The FDP-123/FDP-124 model card uses `allowedUsageModes`, not `approvedFor`.

The existing FDP-103/FDP-102 path keeps its older `approvedFor` contract. approvedFor is limited to SHADOW and
COMPARE. OFFLINE_EVALUATION is not an approval target in that older contract.

approvedFor is limited to SHADOW and COMPARE.
OFFLINE_EVALUATION is not an approval target.

Allowed usage modes are limited to:

- `SHADOW`
- `COMPARE`
- `OFFLINE_EVALUATION`

These usage modes are not runtime permissions. They do not approve production decisioning and do not authorize any
runtime behavior.

The card always keeps:

- `productionApproval = NOT_APPROVED`
- `promotionStatus = NOT_EVALUATED_FOR_PROMOTION`

Evaluation labels are bounded analyst feedback signals, not legal ground truth, certified labels, model-training
labels, final bank decisions, payment decisions, or automatic decisioning signals.

## Output

The FDP-123/FDP-124 writer produces local/internal artifacts:

- `model_card.json`
- `model_card.md`
- `manifest.json`

The output manifest uses:

- `reportType = MODEL_CARD_V1`
- `artifactSetVersion = model-card-artifact-set-v1`

The writer uses a manifest-last pattern. It prepares payloads in memory, validates JSON and Markdown safety, writes
temporary files, replaces `model_card.json`, replaces `model_card.md`, and replaces `manifest.json` last. A model card
artifact set is complete only when `manifest.json` exists and all listed hashes and sizes match.

The output does not include raw or per-record data such as evaluation record identifiers, transaction references,
feedback identifiers, customer identifiers, correlation identifiers, notes, raw payloads, raw model requests or
responses, raw feature vectors, raw evidence, per-record disagreement rows, tokens, secrets, or passwords.

Unavailable metric objects preserve the FDP-124 shape:

```json
{"available":false,"reason":"NO_POSITIVE_RECORDS","value":null}
```

Unavailable metrics are not converted to zero, omitted, or flattened.
