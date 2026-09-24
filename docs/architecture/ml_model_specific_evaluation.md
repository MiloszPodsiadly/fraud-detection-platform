# ML Model Specific Evaluation

Status: FDP-139 offline lineage evidence foundation.

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

The current feedback dataset does not carry direct ML prediction outputs with enough fidelity to claim ML model
precision, recall, threshold, or score-ranking metrics. Those metrics remain unavailable with
`MODEL_PREDICTION_SIGNAL_UNAVAILABLE` until a future contract deliberately adds direct ML output evidence.

ML Model Evaluation is not a model evaluation card, not model promotion approval, not threshold recommendation, not
production-primary approval, not payment authorization, not workflow automation, and not a runtime model switch.
FDP-140 can consume this as trustworthy model-specific lineage evidence, but approval and lifecycle decisions remain
out of scope.
