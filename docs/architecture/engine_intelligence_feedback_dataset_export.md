# Engine Intelligence Feedback Dataset Export

Status: FDP-102 internal service foundation.

## Purpose

The engine-intelligence feedback dataset export is an internal alert-service foundation for offline evaluation input.
It creates bounded evaluation records from analyst feedback, current alert analyst decision state, and projected engine
intelligence. It does not evaluate models, train models, promote models, change thresholds, change scoring behavior,
produce recommendations, authorize payments, or mutate alerts, fraud cases, Kafka events, OpenAPI, or UI.
FDP-102 introduces an internal service foundation only. It is not an approved export surface. The presence of a Spring
service does not authorize public, operator-triggered, scheduled, CLI, or external export.

## Labels

The exported label field is `evaluationLabel`. Analyst feedback and alert decisions are analyst-decision evaluation
categories, not ground truth, not model training labels, not final decisions, not payment decisions, and not automatic
decisioning signals. `CONFIRMED_FRAUD` maps to `ANALYST_CONFIRMED_FRAUD`; `MARKED_LEGITIMATE` maps to
`ANALYST_MARKED_LEGITIMATE`; inconclusive, needs-more-info, missing decision, and unknown decision values map to
`NOT_EVALUATION_ELIGIBLE`. `NOT_EVALUATION_ELIGIBLE` records are excluded from model-quality metrics by downstream
evaluation tooling. Inconclusive and missing decisions must never be treated as negative examples. Raw workflow
decision fields such as `alertAnalystDecision`, submitted-by values, analyst IDs, or other actor identity fields are
not exported.

## Sources And Missing Data

Records are assembled from:

- `engine_intelligence_feedback`, using feedback submission time as the single time basis;
- the current alert analyst decision state;
- the engine-intelligence projection for comparison, engine status, score buckets, risk levels, and diagnostic codes.

Labels are not derived from model output, risk level, alert severity, or projection agreement. Feedback, alert, and
projection transaction IDs must match before any dataset record is emitted. FDP-102 exports only supported projection
contract version `1`; unsupported projection versions fail closed as `CORRUPTED_PROJECTION`. Unsupported projection
does not mean no fraud or low risk. Missing ML score does not mean zero. Missing ML risk does not mean `LOW`. Missing
rules score does not mean zero. Missing rules risk does not mean `LOW`. Missing projection is exported explicitly as
`projectionStatus = MISSING`; it does not mean no fraud. Old transactions without engine intelligence do not crash the
export.

Corrupted feedback, alert, or projection data fails closed by default. Store failures return bounded failure reasons
such as `FEEDBACK_STORE_UNAVAILABLE`, `ALERT_STORE_UNAVAILABLE`, or `PROJECTION_STORE_UNAVAILABLE`; raw exception text
and stack traces must not leave the service result or JSONL output.

## Bounds And Deduplication

The single declared time basis is `FEEDBACK_SUBMITTED_AT`.

Filtering uses:

```text
fromInclusive <= submittedAt <= toInclusive
```

Ordering is deterministic:

```text
submittedAt DESC, feedbackId ASC
```

The date range is capped at 31 days. `maxRecords` is capped from 1 through 500. The repository query is bounded and
does not scan the full collection. The feedback collection has a `submittedAt DESC, feedbackId ASC` index for this
export foundation.

The repository fetches `maxRecords + 1` raw feedback rows internally so truncation can be detected. Deduplication uses
`transactionReference` as the key. Because rows are already ordered by newest submitted feedback, the first row for a
transaction reference wins; `feedbackId ASC` is the stable tie-breaker. The export result exposes `rawRowsRead`,
`recordsReturned`, `truncated`, `timeBasis`, and `deduplicationPolicy` so bounded sampling and partial exports are
visible. `rawRowsRead` is the number of raw rows returned by the bounded query before deduplication.
`recordsReturned` is the number of unique dataset records after deduplication. `recordsReturned < maxRecords` does not
imply the full time window is exhausted. `truncated = true` means more raw feedback rows exist beyond the bounded read.
This is a bounded sample export, not an exhaustive dataset export.

FDP-102 performs bounded per-row alert/projection lookups within the `maxRecords + 1` raw feedback window. This is
acceptable for internal foundation scope. Any future public/operator/scheduled export must revisit this with
performance review and likely batch lookup.

## Safety

The export uses pseudonymous identifiers:

- `evaluationRecordId`
- `transactionReference`

`evaluationRecordId` and `transactionReference` are deterministic internal pseudonymous references derived from source
identifiers. They are not raw payment/core banking IDs, but they are also not anonymized identifiers and not a
cryptographic privacy boundary. Because the references are deterministic, they may be linkable across exports and may
be vulnerable to dictionary matching by parties that know candidate source identifiers. FDP-102 accepts this only
because the branch is internal-only and adds no public API, operator-triggered endpoint, scheduled job, external
export, or runtime trigger. Future public/operator/external export must use a privacy-reviewed identifier strategy
such as keyed HMAC, tokenization, rotation, or another approved approach.

It must not expose raw payment/core transaction identifiers, customer, account, card, device, or merchant identifiers,
PAN, IBAN, email, phone, analyst identifiers, `submittedBy`, correlation IDs, idempotency keys, request hashes, raw
payloads, raw feature vectors, raw evidence, raw contributions, raw ML requests or responses, endpoints, tokens,
secrets, stack traces, exception messages, metadata maps, ground truth labels, model training labels, final decisions,
or payment authorization data.

Reason codes and diagnostic signals are bounded machine-readable values. They are not arbitrary text and must not
contain colon-delimited raw identifiers such as `deviceId:abc` or raw-id patterns such as `customer_123_flagged`.
Forbidden-term and forbidden-pattern checks are contract safeguards, not data-loss-prevention controls. Source
mappers and producers remain responsible for sanitizing data before export.

## JSONL Semantics

`export()` returns `EngineIntelligenceFeedbackDatasetExportResult`, a wrapper containing metadata and records.
`exportJsonl()` emits line-oriented JSON:

```text
{"type":"EXPORT_METADATA", ...}
{"type":"DATASET_RECORD", "record": {...}}
```

The first line carries truncation, time-basis, deduplication, count, and failure metadata. Subsequent lines contain
one dataset record per line. Empty exports still emit the metadata line. `failureReason != null` means the export
failed. Failed exports emit metadata with a bounded `failureReason` and no partial successful-looking record stream.
Failed exports must not contain dataset records. Consumers must treat `failureReason != null` as a hard export
failure. A failed export metadata line is not a successful empty dataset. Consumers must abort processing and must not
count the file as an evaluation input.

## Scope

FDP-102 adds no public API, OpenAPI path, UI, scheduled job, CLI job, Python evaluation runner, Kafka change,
`TransactionScoredEvent` change, model retraining, model promotion, threshold switching, automatic decisioning,
recommendation service, payment authorization, alert severity mutation, or fraud-case status mutation.

Any future public/operator/scheduled/external export requires a separate scoped PR with authorization, sensitive-read
audit, rate limits, privacy review, retention policy, access controls, operational monitoring, and an approved
privacy-reviewed identifier strategy. Public or operator-triggered export is a separate future scope. The Python ML
Evaluation Suite is also separate future scope after this internal dataset-export foundation is merged.
