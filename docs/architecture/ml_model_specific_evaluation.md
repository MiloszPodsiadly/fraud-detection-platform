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

The trusted model-evaluation artifact reader accepts only a `model-evaluation/` directory containing exactly
`model_evaluation_summary.json` and `manifest.json`. Before returning read-only evidence it bounds both files, rejects
symlinks and noncanonical manifest paths, verifies the canonical report and artifact-set identity, checks `sizeBytes`
and SHA-256 against the actual summary bytes, validates the summary contract, and requires matching `generatedAt`
values. Unknown files, malformed manifests, and tampered summaries fail closed.

Public JSON Schema, OpenAPI, and frontend model-identity contracts enforce the canonical structural syntax:
`modelName` and `modelVersion` are bounded to 64 characters, `featureContractVersion` is bounded to 96 characters,
and each uses `^[A-Za-z0-9._-]+$`. The Java and Python runtime identity policies deliberately add sensitive and
forbidden semantic-term rejection. A value may therefore be structurally valid for transport while still being
rejected at the runtime trust boundary; public schemas do not claim to implement that additional security policy.

The current feedback dataset does not carry direct ML prediction outputs with enough fidelity to claim ML model
precision, recall, threshold, or score-ranking metrics. Those metrics remain unavailable with
`MODEL_PREDICTION_SIGNAL_UNAVAILABLE` until a future contract deliberately adds direct ML output evidence.

ML Model Evaluation is not a model evaluation card, not model promotion approval, not threshold recommendation, not
production-primary approval, not payment authorization, not workflow automation, and not a runtime model switch.
FDP-140 can consume this as trustworthy model-specific lineage evidence, but approval and lifecycle decisions remain
out of scope.
