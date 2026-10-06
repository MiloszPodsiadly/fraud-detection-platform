# ML Model Specific Evaluation

Status: active offline exact-model classification evaluation.

The feedback dataset evaluation package now supports two separate offline diagnostic subjects.

Platform Recommendation Evaluation answers:

- How did the platform recommendation behave against bounded analyst feedback?
- It keeps `subjectType = PLATFORM_RECOMMENDATION`.
- It keeps `modelIdentity = NOT_AVAILABLE` and `modelArtifactSha256 = NOT_AVAILABLE`.
- It remains the source for the existing Platform Recommendation Evaluation Card path.

ML Model Evaluation answers:

- What bounded evidence exists for exact ML model identity X?
- It uses `subjectType = ML_MODEL`.
- It requires exact `modelName`, `modelVersion`, and `featureContractVersion`.
- It never uses latest runtime model, majority version, registry state, or current deployment state.

The model-specific path uses an explicit exact-identity policy. Records whose lineage exactly matches the requested
identity are evaluated. Records with missing lineage are counted as `MODEL_LINEAGE_UNAVAILABLE` and excluded. Records
with complete but different lineage are counted as `MODEL_IDENTITY_MISMATCH` and excluded. Unknown lineage is never
merged into a known model group.

The model-specific report is aggregate-only. It contains counts, class balance, evaluation window, limitations, and
explicit unavailable metric reasons. It does not emit `evaluationRecordId`, `transactionReference`, raw identifiers,
raw feature vectors, raw ML requests or responses, or per-record examples.

Each model summary contains one canonical `sourceDataset` identity: the dataset version, SHA-256 of the exact bounded
JSONL bytes consumed by the reader, and the source population counters. The digest is computed before parsing and is
not a digest of reserialized records. Source accounting remains distinct from model accounting and must reconcile as
`rawRowsRead` to `recordsReturned` to model `recordsConsidered`, followed by the model-specific evaluated and excluded
populations. This proves which immutable source bytes and returned population were evaluated for the requested exact
model; it does not make analyst feedback legal ground truth or prove approval, promotion readiness, calibration,
payment authorization, or suitability for production-primary decisioning.

The trusted model-evaluation artifact reader accepts only a `model-evaluation/` directory containing exactly
`model_evaluation_summary.json` and `manifest.json`. Before returning read-only evidence it bounds both files, rejects
symlinks and noncanonical manifest paths, verifies the canonical report and artifact-set identity, checks `sizeBytes`
and SHA-256 against the actual summary bytes, validates the summary contract, and requires matching `generatedAt`
values. Because the manifest protects the summary bytes, it transitively protects the embedded source identity; the
source digest is not duplicated in the manifest. When the trusted reader is given the source JSONL, it also recomputes
and compares the complete source identity. Without those bytes it validates only the immutable identity claimed by the
artifact and does not fetch or infer a source dataset. Unknown files, malformed manifests, and tampered summaries fail
closed.

Public JSON Schema, OpenAPI, and frontend model-identity contracts enforce the canonical structural syntax:
`modelName` and `modelVersion` are bounded to 64 characters, `featureContractVersion` is bounded to 96 characters,
and each uses `^[A-Za-z0-9._-]+$`. The Java and Python runtime identity policies deliberately add sensitive and
forbidden semantic-term rejection. A value may therefore be structurally valid for transport while still being
rejected at the runtime trust boundary; public schemas do not claim to implement that additional security policy.

Feedback dataset v2 carries the exact immutable ML score, risk level, execution timestamp, and complete model identity
for records whose evidence status is `AVAILABLE`. Legitimate absence remains explicit and carries null prediction
values. Model-specific classification uses only that direct `mlPredictionRiskLevel`: `HIGH` and `CRITICAL` are a
positive prediction, while `LOW` and `MEDIUM` are a negative prediction under
`RISK_HIGH_OR_CRITICAL_POSITIVE_V1`. Platform `riskLevel`, `fraudScore`, and `alertRecommended` are not valid
substitutes.

The model-specific Rules-vs-ML aggregate compares that exact ML risk only with the Rules risk snapshotted from the
same feedback scoring occurrence. It counts ML-high/Rules-low, Rules-high/ML-low, both-high, and both-low outcomes.
Rows without Rules evidence are reported separately and never become low risk; rows excluded by exact model identity
cannot contribute a Rules signal. The aggregate contains counts only, not record or transaction identifiers.

The model summary reconciles every considered row as evaluated, legitimately missing prediction evidence,
unexpectedly missing prediction evidence, invalid prediction evidence, missing lineage, or exact-identity mismatch.
`recordsWithPredictionEvidence` counts only rows carrying usable direct ML prediction evidence. A complete direct
identity that differs from the requested model is counted as a model identity mismatch with evidence; an upstream
source/projection identity conflict has no exported direct prediction and is counted separately as
`recordsExcludedSourceIdentityMismatch`.

Warnings are deterministic evidence-quality facts derived from that population. `UNEXPECTED_ML_EVIDENCE_LOSS`
records unexpected missing direct evidence, `INVALID_ML_EVIDENCE_PRESENT` records malformed evidence, and
`MODEL_EVALUATION_PARTIAL_COVERAGE` records that fewer rows were evaluated than considered. They define no approval
threshold and do not approve or reject promotion, recommend threshold changes, or influence payment decisions.

Its aggregate confusion matrix reports TP, FP, TN, and FN plus precision, recall, true-positive rate,
false-positive rate, and false-negative rate. A zero denominator is an explicit unavailable metric with a
machine-code reason; it is never emitted as NaN, infinity, or an invented zero.

Model-specific `precisionAtK` and `recallAtK` rank only the requested exact model population by
`mlPredictionScore DESC`, with `evaluationRecordId ASC` as the deterministic tie-break. K is bounded to the dataset
limit; when K exceeds the eligible population, `actualK` is the population size. Missing direct prediction evidence
never becomes score zero and never enters the ranking. The score is an ordering signal, not a calibrated probability.

`MODEL_PREDICTION_SIGNAL_UNAVAILABLE` is present only when no row has usable exact prediction evidence for the
requested model identity. Single-class evidence remains visible through `SINGLE_CLASS_MODEL_LINEAGE_RECORDS`, while
the affected rates stay explicitly unavailable.

ML Model Evaluation is not a model evaluation card, not model promotion approval, not threshold recommendation, not
production-primary approval, not payment authorization, not workflow automation, not calibration, and not a runtime model switch.
Downstream governance checks can consume this as trustworthy model-specific lineage evidence, but approval and lifecycle decisions remain
out of scope.
