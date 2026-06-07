# Engine Intelligence Feedback Dataset Export

Status: FDP-102 internal service foundation.

## Purpose

The engine-intelligence feedback dataset export is an internal alert-service foundation for offline evaluation input.
It creates bounded evaluation records from analyst feedback, current alert analyst decision state, and projected engine
intelligence. It does not evaluate models, train models, promote models, change thresholds, change scoring behavior,
produce recommendations, authorize payments, or mutate alerts, fraud cases, Kafka events, OpenAPI, or UI.

## Labels

The exported label field is `evaluationLabel`. Analyst feedback and alert decisions are evaluation signals, not
ground truth and not model training labels. `CONFIRMED_FRAUD` maps to `POSITIVE`; `MARKED_LEGITIMATE` maps to
`NEGATIVE`; inconclusive, needs-more-info, missing decision, and unknown decision values map to `NON_TRAINING`.
`NON_TRAINING` records are excluded from model-quality metrics by downstream evaluation tooling. Inconclusive and
missing decisions must never be treated as negative examples.

## Sources And Missing Data

Records are assembled from:

- `engine_intelligence_feedback`, using feedback submission time as the single time basis;
- the current alert analyst decision state;
- the engine-intelligence projection for comparison, engine status, score buckets, risk levels, and diagnostic codes.

Labels are not derived from model output, risk level, alert severity, or projection agreement. Missing ML score does
not mean zero. Missing ML risk does not mean `LOW`. Missing rules score does not mean zero. Missing rules risk does
not mean `LOW`. Missing projection is exported explicitly as `projectionStatus = MISSING`; it does not mean no fraud.
Old transactions without engine intelligence do not crash the export.

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

Deduplication uses `transactionReference` as the key. Because rows are already ordered by newest submitted feedback,
the first row for a transaction reference wins; `feedbackId ASC` is the stable tie-breaker. The export result exposes
`rawRowsRead`, `recordsReturned`, `truncated`, `timeBasis`, and `deduplicationPolicy` so bounded sampling and partial
exports are visible.

## Safety

The export uses pseudonymous identifiers:

- `evaluationRecordId`
- `transactionReference`

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
one dataset record per line. Empty exports still emit the metadata line. Failed exports emit metadata with a bounded
`failureReason` and no partial successful-looking record stream.

## Scope

FDP-102 adds no public API, OpenAPI path, UI, scheduled job, CLI job, Python evaluation runner, Kafka change,
`TransactionScoredEvent` change, model retraining, model promotion, threshold switching, automatic decisioning,
recommendation service, payment authorization, alert severity mutation, or fraud-case status mutation.

Public or operator-triggered export is a separate future scope and requires authorization, sensitive-read audit,
rate limits, privacy review, and retention policy. The Python ML Evaluation Suite is also separate future scope after
this internal dataset-export foundation is merged.
