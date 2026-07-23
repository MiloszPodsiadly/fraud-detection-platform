# Platform Recommendation Evaluation Card v1

Status: offline governance artifact. Platform Recommendation Evaluation Card v1 is an offline governance artifact.

## Scope

Platform Recommendation Evaluation Card v1 has one executable implementation in this repository:

- `ml-inference-service/offline_evaluation/fdp123/evaluation_card/`

The previous FDP-102/FDP-103 Platform Recommendation Evaluation Card modules were removed. Platform Recommendation Evaluation Card v1 now consumes only FDP-124 aggregate
artifacts generated from the FDP-123 feedback dataset path. It validates the canonical FDP-124 `manifest.json` before
trusting the canonical `evaluation_summary.json`. It does not read `disagreement_report.jsonl` in v1 and does not
read the raw FDP-123 dataset.

This support is governance documentation only. It is not model promotion, not production approval, not threshold
recommendation, not payment authorization, not workflow or case automation, and not legal ground truth. It does not
expose public API, admin API, UI, scheduler, database writes, Kafka events, network calls, or external publishing.
It does not promote models, does not recommend thresholds, does not approve, decline, or block transactions, and does not authorize payments.
Dashboards and promotion workflows are future scopes.

## Inputs

The generator accepts only:

- FDP-124 `manifest.json`
- FDP-124 `evaluation_summary.json`
- local governance metadata for non-identity usage/boundary lists

The accepted source artifact identity is:

- `reportType = FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1`
- `artifactSetVersion = fdp123-report-artifact-set-v1`

The source summary basename must be exactly `evaluation_summary.json`, and the source manifest basename must be
exactly `manifest.json`. Manifest validation requires exactly one `files[].name = evaluation_summary.json` entry and
preserves the `sha256` and `sizeBytes` integrity checks for that entry.

Input JSON artifacts are bounded before full read:

- `manifest.json` maximum size: 65,536 bytes.
- `evaluation_summary.json` maximum size: 262,144 bytes.

FDP-124 `evaluation_summary.json` is the only source of evaluation identity. It carries a closed
`evaluationSubject`:

- `subjectType = PLATFORM_RECOMMENDATION`
- `sourceComponent = ENGINE_INTELLIGENCE_PROJECTION`
- `sourceVersion = ENGINE_INTELLIGENCE_PROJECTION_V1`
- `featureContractVersion = NOT_APPLICABLE`
- `modelIdentity = NOT_AVAILABLE`
- `modelArtifactSha256 = NOT_AVAILABLE`
- `identityCompleteness = NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE`

The Platform Recommendation Evaluation Card generator copies that subject from FDP-124 and rejects unsupported subject, `metricsSubject`, or
`metricBasis` values. CLI callers cannot set model name, model version, model family, training mode, feature contract,
reference quality, or artifact identity.

The metric owner and basis are explicit:

- `metricsSubject = PLATFORM_RECOMMENDATION`
- `metricBasis = ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK`

`alertRecommendedConfusionMatrix` is therefore a platform recommendation diagnostic against bounded analyst feedback
signals. It is not represented as direct model performance or model promotion evidence.

For the binary FDP-123/FDP-124 evaluation contract, class-count integrity is strict:

```text
positiveClassCount + negativeClassCount == recordsEvaluated
```

The invariant is enforced at the FDP-124 source boundary and again when validating final Platform Recommendation Evaluation Card
`evaluationEvidence`. Evidence counts use the single FDP-123 hard limit, `MAX_DATASET_RECORDS = 1000`.

Timestamps must be real RFC3339 date-times with explicit timezone. Accepted timestamps are normalized to UTC `Z`.
The final Platform Recommendation Evaluation Card `generatedAt` instant must be greater than or equal to
`evaluationEvidence.evaluationGeneratedAt`.

## Semantics

The card uses `allowedUsageModes`, not `approvedFor`.

Allowed usage modes are limited to:

- `SHADOW`
- `COMPARE`
- `OFFLINE_EVALUATION`

These values are documentation semantics only. They do not approve production decisioning and do not authorize any
runtime behavior.

The card always keeps:

- `productionApproval = NOT_APPROVED`
- `promotionStatus = NOT_EVALUATED_FOR_PROMOTION`

Required limitations include `METRICS_ARE_PLATFORM_RECOMMENDATION_DIAGNOSTICS`. Evaluation labels are bounded analyst
feedback signals, not legal ground truth, certified labels, model-training labels, final bank decisions, payment
decisions, or automatic decisioning signals.
`intendedUse` is allowlisted, and required `notIntendedUse` non-goals cannot be omitted.

FDP-124 `disagreementSummary` is not part of the Platform Recommendation Evaluation Card v1 contract. The v1 generator does not copy it into
`metricsSummary`, does not silently filter malformed members, and does not transform it into a valid-looking Model
Card field.

## Output

The writer produces local/internal artifacts:

- `evaluation_card.json`
- `evaluation_card.md`
- `manifest.json`

The output manifest uses:

- `reportType = PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1`
- `artifactSetVersion = platform-recommendation-evaluation-card-artifact-set-v1`

The writer uses a manifest-last pattern. A evaluation card artifact set is complete only when `manifest.json` exists and
all listed hashes and sizes match. The CLI requires `--allow-output-root`.

The output does not include raw or per-record data such as evaluation record identifiers, transaction references,
feedback identifiers, customer identifiers, correlation identifiers, notes, raw payloads, raw model requests or
responses, raw feature vectors, raw evidence, per-record disagreement rows, tokens, secrets, or passwords.

`sourceManifestSha256` is a local lineage and integrity fingerprint of the source `manifest.json` bytes consumed by
the generator. It is not a signature, notarization, external attestation, immutability guarantee, independent trust
anchor, or proof that the source artifact was produced by a trusted party.

Unavailable metric objects preserve the FDP-124 shape:

```json
{"available":false,"reason":"NO_POSITIVE_RECORDS","value":null}
```

Unavailable metrics are not converted to zero, omitted, or flattened.
