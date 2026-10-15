# Exact-Artifact ML Shadow Performance

Status: implemented.

## Decision

Exact-artifact ML Shadow Performance must extend the existing offline evaluation and governance artifact chain. Python
remains the only metric owner. Java validates and exposes immutable aggregate artifacts. React renders the validated
API response and does not recalculate metrics. The existing Platform Recommendation Shadow Performance capability is
distinct business functionality and remains in place.

Two populations must never be conflated:

- `LABELED_FEEDBACK` contains at most 1000 governed Feedback Dataset v3 records selected by
  `feedbackCreatedAt`. It supports outcome metrics against analyst review signals.
- `SCORING_OCCURRENCES` would contain admitted `TransactionScoredEvent` v2 occurrences selected by scoring-event
  time. Such a governed evaluation population does not exist yet, and it would not contain outcome labels.

No metric may be described as scoring-wide when its denominator comes from Feedback Dataset v3. Analyst labels are
review signals, not certified ground truth. Neither population grants model promotion, threshold change, payment,
workflow, or production-primary decision authority.

## Source Of Truth

| Concern | Authoritative owner | Current evidence | Decision |
| --- | --- | --- | --- |
| Feedback eligibility and bounded extraction | `FeedbackDatasetEligibilityPolicy` and `FeedbackDatasetBuilder` | Confirmed fraud or legitimate labels only; unresolved and governance-review rows excluded; 1000-row bound; `FEEDBACK_CREATED_AT` | KEEP |
| Exact prediction evidence on labeled rows | Feedback Dataset v3 ML evidence fields | Score, risk, execution time, model name/version, feature contract, artifact SHA-256, status, provenance, and omission reason | KEEP |
| Exact-model outcome metrics | Python `model_evaluation.py` and shared `metrics.py` | Exact-identity filtering, confusion matrix, rates, rank metrics, and Rules/ML disagreement | EXTEND; do not create another evaluator |
| Evaluation integrity | Python model-evaluation artifact reader/writer | Strict JSON, bounded regular files, exact directory contents, manifest digest and size, optional source-byte verification | KEEP |
| Exact model governance card | Python Model Card generator and publisher | Model bytes verified, exact identity, optional evaluation evidence, atomic no-replace version publication | KEEP and reuse as identity context |
| Platform recommendation diagnostics | Python Shadow Performance Summary v2 | `alertRecommended` against bounded analyst feedback; platform identity intentionally unavailable | KEEP as separate functionality |
| Scoring-occurrence facts | `TransactionScoredEvent` v2 and alert-service evidence projections | Event identity, event time, final platform result, exact ML evidence or explicit omission, bounded Engine Intelligence | EXTEND only through a governed bounded export; current Mongo projections are not evaluation artifacts |
| Artifact consumption | alert-service governance providers and `GovernanceArtifactReader` | Base-directory confinement, symlink rejection, bounded stable reads, strict fields, manifest integrity, `404`/`503` separation | EXTEND existing pattern |
| Authorization and audit | `GovernanceAuthorizationRules` and sensitive-read audit infrastructure | Explicit authorities, authenticated read boundary, classified audit resource, environment-dependent fail-closed auditing | EXTEND existing route group; never rely on UI gating |
| Presentation | Analyst Console governance workspace | API client boundary, capability-aware loading, strict response validation, no fabricated fallback values | EXTEND existing workspace and components |
| Runtime mount | Compose governance overlays | Explicit enablement and read-only mounts for generated Shadow Performance and exact-version Model Card artifacts | EXTEND with one read-only exact-version artifact mount |

## Metric Inventory

All calculations below belong in Python. Java and React consume the resulting values without recomputation.

### Labeled Feedback Metrics

The source is Feedback Dataset v3. Eligibility requires a binary evaluation candidate and usable exact ML prediction
evidence matching the requested `mlModelName`, `mlModelVersion`, `mlFeatureContractVersion`, and
`mlModelArtifactSha256`. Complete nonmatching identity, source-identity conflict, legitimate absence, unexpected
absence, and malformed evidence are excluded into separate counters rather than converted to negative predictions.
The model-evaluation artifact repeats that identity as `evaluationSubject` and binds its source claim to the exact
JSONL SHA-256. The claim is `CLAIM_ONLY` unless the reader receives and matches the source bytes, in which case it is
`VERIFIED_AGAINST_SOURCE_BYTES`.

| Metric | Exact source fields | Numerator / denominator | Coverage, status, and required change |
| --- | --- | --- | --- |
| Positive and negative class counts | `evaluationLabel` | Count of evaluated `POSITIVE_FRAUD` / `NEGATIVE_LEGITIMATE` rows | Bounded exact-identity cohort; IMPLEMENTED, reuse |
| TP, FP, TN, FN | `evaluationLabel`, `mlPredictionRiskLevel` | Exact-model high/critical prediction versus binary feedback label | `RISK_HIGH_OR_CRITICAL_POSITIVE_V1`; IMPLEMENTED, reuse |
| Precision | Derived TP and FP | `TP / (TP + FP)` | Unavailable with `NO_PREDICTED_POSITIVES`; IMPLEMENTED, reuse |
| Recall and true-positive rate | Derived TP and FN | `TP / (TP + FN)` | Unavailable with `NO_ACTUAL_POSITIVES`; IMPLEMENTED, reuse |
| False-positive rate | Derived FP and TN | `FP / (FP + TN)` | Unavailable with `NO_ACTUAL_NEGATIVES`; IMPLEMENTED, reuse |
| False-negative rate | Derived FN and TP | `FN / (FN + TP)` | Unavailable with `NO_ACTUAL_POSITIVES`; IMPLEMENTED, reuse |
| Precision at K | `mlPredictionScore`, `evaluationRecordId`, `evaluationLabel` | Positive labels in the top-K exact-model scores / actual K | Score descending and record ID ascending; IMPLEMENTED, reuse |
| Recall at K | `mlPredictionScore`, `evaluationRecordId`, `evaluationLabel` | Positive labels in the top-K exact-model scores / all positive labels | Same deterministic bounded ranking; IMPLEMENTED, reuse |
| Rules/ML disagreement | `mlPredictionRiskLevel`, `rulesEvidenceStatus`, `rulesRiskLevel` | Counts of ML-high/Rules-low, Rules-high/ML-low, both-high, and both-low; disagreement rate is the two directional disagreements divided by `recordsCompared` | Rows without Rules evidence excluded; IMPLEMENTED |
| Analyst-labeled fraud by disagreement category | `evaluationLabel` plus the Rules/ML category | Positive feedback rows in each category, with unavailable Rules evidence counted separately | Descriptive labeled-feedback evidence only; IMPLEMENTED |
| Exact-artifact feedback coverage | Population counters and exact identity fields | `recordsEvaluated / recordsConsidered` with every exclusion count retained | Feedback-only descriptive fact; EXTEND only if a ratio is required |

The time window is inclusive and uses `feedbackCreatedAt`. `scoredAt`, transaction time, ML execution time, and report
generation time remain provenance fields and must not silently replace the cohort time basis.

### Scoring Occurrence Metrics

`TransactionScoredEvent` v2 enforces exactly one ML evidence outcome: direct immutable evidence or an explicit
omission reason. That contract is sufficient evidence for a future bounded occurrence export, but no repository-owned
artifact currently proves a complete scoring-occurrence cohort for a requested exact model and time window.

| Metric | Required source fields | Required numerator / denominator | Current status and required change |
| --- | --- | --- | --- |
| Exact-artifact execution count | `eventId`, `createdAt`, `mlPredictionEvidence.modelIdentity` | Matching complete exact identity / admitted occurrences in the event-time window | MISSING governed bounded source |
| ML evidence availability | Direct evidence or `mlPredictionEvidenceOmissionReason` | Direct ML evidence / occurrences eligible for ML diagnostic execution | MISSING; define and prove the eligibility denominator |
| Omission distribution | `mlPredictionEvidenceOmissionReason` | Count by omission reason / occurrences without direct evidence | Raw facts IMPLEMENTED; MISSING aggregate artifact |
| ML risk distribution | Exact model identity and `mlPredictionEvidence.mlRiskLevel` | Count by ML risk / matching occurrences with direct evidence | Raw facts IMPLEMENTED; MISSING aggregate artifact |
| Rules/ML operational disagreement | Exact ML evidence and bounded `engineIntelligence.engines` results | Four canonical category counts / occurrences with both usable engine results | Raw facts IMPLEMENTED; MISSING aggregate artifact |
| Score-bucket distribution | Exact model identity and `mlPredictionEvidence.mlScore` | Count per approved bucket / matching occurrences with a valid score | Raw facts IMPLEMENTED; MISSING aggregate artifact |
| Outcome accuracy or drift | No outcome label exists on a scoring occurrence | Requires later labels joined to exact occurrences | UNAVAILABLE here; use labeled feedback and name it truthfully |

Until a bounded occurrence source establishes admission completeness, event-time bounds, deduplication by immutable
occurrence identity, exact model identity, and exclusion accounting, scoring-wide rates must be reported as
`UNAVAILABLE`. Querying the mutable `scored_transactions` projection cannot prove historical occurrence completeness
because it owns the current transaction projection rather than an append-only evaluation population.

## Minimal Implementation Boundary

1. Extend the existing Python exact-model evaluation package with one canonical exact-artifact shadow report contract.
   Reuse shared classification and ranked metric functions instead of copying formulas.
2. Compose labeled-feedback metrics from the validated model-evaluation artifact set. Add scoring-occurrence metrics
   only after a bounded authoritative occurrence artifact exists; otherwise emit explicit unavailability and coverage
   facts.
3. Publish one immutable, exact-version directory with canonical JSON and manifest through the existing atomic
   no-replace publication primitive. Selection must name the exact published directory, never `latest`.
4. Extend alert-service governance ownership with a strict provider, validator, read service, controller, dedicated
   read authority, sensitive-read classification, OpenAPI contract, and low-cardinality read outcomes. Reuse
   `GovernanceArtifactReader`.
5. Extend the existing Analyst Console governance workspace and API client. Render server-owned metrics, population,
   identity, exclusions, warnings, and unavailable reasons. Preserve independent loading and authorization state.
6. Add an explicit read-only Compose mount and disabled-by-default application configuration. Artifact generation and
   publication remain outside alert-service.

The existing Platform Recommendation endpoint and dashboard must not be repurposed: their subject is the platform
recommendation and their metric basis is `ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK`. The exact-artifact surface
must have a distinct contract and identity while sharing infrastructure and presentation patterns.

## Cleanup Audit

No deletion is currently proven in the affected runtime paths.

- Platform Recommendation Shadow Performance has active Python generation, Java validation/API, React presentation,
  Compose, fixtures, CI, and architecture-test callers. It represents distinct business semantics and is retained.
- Model Evaluation and Model Card artifacts are current exact-identity evidence used across Python, Java, and React.
  Version tokens in their canonical contract names do not make them obsolete.
- `TransactionScoredEvent` v2 and its ML evidence/omission invariant are current integration contracts and are
  retained unchanged.
- Feedback Dataset v3 is the only governed labeled cohort and is retained. It must not be replaced by current scoring
  projections or expanded into an unbounded export.
- No compatibility adapter, duplicate evaluator, parallel registry, or superseded exact-artifact shadow runtime was
  found. Therefore the confirmed obsolete-code set is empty for this audit.

## Regression Risks And Gates

- Reject any report that omits the complete exact model identity or mixes identities in one evaluated population.
- Reconcile source, considered, evaluated, and every excluded population; fail closed on impossible counts.
- Preserve explicit unavailable metric reasons for empty or single-class denominators; never emit NaN, infinity, or a
  synthetic zero.
- Preserve source-byte verification level. Manifest integrity alone is not proof that claimed source bytes were used.
- Keep analyst feedback, scoring occurrence, transaction, execution, and generation timestamps semantically distinct.
- Keep artifacts aggregate-only and bounded; exclude raw identifiers, pseudonymous row references, features,
  requests, responses, secrets, and per-record examples.
- Verify canonical serialization, digest and size, exact directory contents, symlink rejection, stable repeated reads,
  and immutable version selection across Python and Java.
- Verify `401`, `403`, `404`, and `503` behavior, dedicated RBAC, sensitive-read auditing, and fail-closed production
  audit policy.
- Verify UI capability gating, malformed-response rejection, no client-side metric derivation, no fallback metrics,
  and independent workspace request state.
- Verify read-only Compose mounts, disabled-by-default base configuration, OpenAPI parity, scope guards, cross-language
  golden fixtures, and continued Platform Recommendation Shadow Performance behavior.

