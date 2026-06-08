# Model Card v1

Status: FDP-104 offline governance artifact.

## Scope

Model Card v1 is an offline governance artifact. It consumes FDP-103 aggregate evaluation reports and bounded model
metadata fixtures or config supplied by the caller. It does not read production DBs, raw payloads, raw feature vectors,
model binaries, model registry state, or raw dataset rows.

Model Card v1 does not mutate model artifacts, does not retrain models, does not promote models, does not recommend thresholds,
does not change production scoring, does not expose API/UI, does not create dashboards, does not recommend
analyst actions, does not approve, decline, or block transactions, and does not authorize payments.

## Inputs

The model-card generator accepts:

- one FDP-103 aggregate evaluation report,
- one bounded model metadata object,
- one caller-provided generation timestamp.

The FDP-103 report is consumed by allowlisted aggregate fields only: report type, report generation time, dataset time
basis, dataset deduplication policy, aggregate input counts, diagnostic quality metrics, missing-signal counts, and
rule-vs-ML disagreement counts. The generator does not pass through the raw report and does not copy per-record data.

Bounded model metadata includes model name, model version, model family, feature contract version, intended use, and
approvedFor values. It is not an arbitrary metadata map and it is not a model artifact.

## Semantics

Metrics are offline diagnostics only. Analyst labels are evaluation signals, not ground truth, model-training labels,
final decisions, payment decisions, or automatic decisioning signals.

approvedFor is limited to SHADOW, COMPARE, and OFFLINE_EVALUATION. These values mean the card can support offline
shadow or compare review. They do not approve production decisioning, model promotion, threshold changes, automatic
approve/decline/block behavior, analyst recommendations, or payment authorization.

Required limitations explicitly state that the card is offline-only, diagnostic-only, bucket-ordered rather than
calibrated probability based, not a promotion approval, not a threshold recommendation, not production decisioning
approval, not payment authorization, and not automatic approve/decline/block permission.

## Output Boundary

Model Card v1 output is deterministic JSON. It rejects raw or unsafe terms including transaction references,
evaluation record identifiers, raw payloads, raw feature vectors, customer/account/card/device/merchant identifiers,
analyst or submitted-by identifiers, correlation or request hashes, endpoints, tokens, secrets, stack traces,
exception messages, ground-truth or training-label terms, final decision terms, payment authorization terms,
production approval terms, promotion-ready terms, threshold recommendation terms, deployment recommendation terms,
and bank-certification wording.

Dashboards and promotion workflows are future scopes and require separate architecture review.
