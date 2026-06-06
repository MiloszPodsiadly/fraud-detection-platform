# Engine Intelligence Feedback Dataset Export Foundation

## Purpose

FDP-101 adds an internal, bounded feedback dataset export foundation for governance-reviewed model evaluation. It
does not add a public API endpoint, analyst-console UI, dashboard, model retraining job, model promotion workflow,
threshold change, rule update, payment authorization behavior, alert severity mutation, or fraud-case status
mutation.

## Scope

The export foundation reads existing engine-intelligence feedback records, existing engine-intelligence projections,
and existing alert analyst-decision state. It emits bounded DTO records or stable JSONL lines for a caller that
already sits inside the alert-service boundary.

## Boundaries

FDP-101 does not create a new `FraudEngineResult` contract and does not change `FraudSignalEngine`. It reuses the
existing bounded projection and feedback persistence models.

The exported DTO never exposes `submittedBy`, correlation identifiers, idempotency hashes, request payload hashes,
raw fraud scores, customer/account/card/device/merchant identifiers, raw evidence, raw feature vectors, raw payloads,
raw stack traces, raw exception text, tokens, secrets, endpoint strings, or arbitrary metadata maps.

## Label Semantics

`feedbackLabel` is an evaluation label, not ground truth and not a model-training label. `CONFIRMED_FRAUD` maps to
`POSITIVE`, `MARKED_LEGITIMATE` maps to `NEGATIVE`, and missing analyst decision state maps to `NON_TRAINING`.
Inconclusive feedback is therefore never treated as negative.

Feedback-only fields such as usefulness and accuracy assessment remain review context. They do not change scores,
rules, alert severity, fraud-case status, or payment authorization.

## Missing Data

Missing ML engine output is represented with null ML risk and score bucket fields. Missing engine-intelligence
projection is represented by `engineIntelligenceProjectionStatus=MISSING`; it is not interpreted as low risk, no
fraud, or analyst confirmation.

Fields that are not available in current persistence, such as model name and model version for the projected engines,
are not invented by FDP-101.

## Bounds And Ordering

Exports require an inclusive bounded date range of at most 31 days and a maximum record count of at most 500. Empty
exports are valid. Ordering is deterministic: newest feedback first by `submittedAt`, with `feedbackId` as the
tie-breaker.

Feedback is append-only, so multiple feedback records can exist for one transaction. FDP-101 emits at most one record
per transaction in a single export window: the newest `submittedAt` feedback wins, with `feedbackId` as the stable
tie-breaker.

## Failure Semantics

Feedback store failures, projection store failures, alert store failures, corrupted source data, and serialization
failures are explicit unavailable states. Projection read failure is not treated as an empty projection set. Missing
projection documents remain explicit missing projection records.

## Production Review Verdict

Verdict: conditionally acceptable foundation.

Score: 4/5.

What is correct: the export is bounded, deterministic, internal-only, no-leakage oriented, and does not modify
decisioning or model runtime paths.

Real risks: future callers still need separate authorization, sensitive-read audit, rate limiting, retention review,
and operational rollout controls before any public or operator-facing export endpoint exists.

Critical fixes only: do not expose this foundation through an endpoint without a separate FDP review covering
authorization, audit, rate limiting, OpenAPI contract, and privacy controls.

Optional improvements: future governance may add export fingerprints and audited access metadata once an approved
operator-facing export surface exists.
