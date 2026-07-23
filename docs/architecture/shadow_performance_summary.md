# Shadow Performance Summary v2

Status: FDP-105 offline diagnostic summary foundation.

## Scope

Shadow Performance Summary v2 is an offline diagnostic artifact. Its Platform Recommendation Evaluation Card consumer path accepts only validated
FDP-123/FDP-124/FDP-126 Platform Recommendation Evaluation Card v1 objects and caller-provided generation timestamps. The local FDP-109 generator
builds a summary directly from the current Platform Recommendation Evaluation Card artifact and does not recreate the removed FDP-102/FDP-103
Platform Recommendation Evaluation Card path or map an FDP-103/FDP-124 report directly into the current summary.

Shadow Performance Summary v2 carries evaluation population and sample-size context with its diagnostic metrics. This
context is required so precision, recall, and false-positive-rate values cannot be interpreted without knowing the
number of records evaluated and class balance.

Shadow Performance Summary v2 does not approve model promotion, does not recommend thresholds, does not approve
production decisioning, does not authorize payments, does not create automatic approve, decline, or block behavior,
and does not recommend analyst actions. It does not expose API, OpenAPI, UI, or dashboards, and does not create
scheduled jobs, DB writes, Kafka messages, scoring changes, registry writes, or model artifact mutations.

## Input

For Platform Recommendation Evaluation Card input, the only supported source of truth is a validated FDP-126 Platform Recommendation Evaluation Card v1:

- `cardType = PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1`
- `cardVersion = platform-recommendation-evaluation-card-v1`
- `evaluationPurpose = OFFLINE_DIAGNOSTIC`
- runtime, promotion, threshold, payment, and workflow authority fields are `NONE`
- `metricsSubject = PLATFORM_RECOMMENDATION`
- `metricBasis = ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK`
- FDP-124 report identity and dataset time basis already validated by Platform Recommendation Evaluation Card v1

The summary builder copies only allowlisted aggregate and governance fields from the validated evaluation card. It does not accept raw evaluation cards as output fields and does not pass through raw reports,
raw dataset rows, pseudonymous
references, raw payloads, raw feature vectors, identifiers, endpoints, tokens, secrets, exception text, or stack traces.

## Output

The output is deterministic compact JSON with:

- summary type, summary version, and generation timestamp
- platform recommendation evaluation subject with explicit `NOT_AVAILABLE` model identity
- diagnostic governance and explicit non-goal booleans
- evaluation context inherited from Platform Recommendation Evaluation Card v1
- evaluation population/sample-size context inherited from Platform Recommendation Evaluation Card v1
- aggregate diagnostic metric availability objects inherited from Platform Recommendation Evaluation Card v1
- bounded warnings and limitations
- the required offline diagnostics banner

`evaluationPopulation` includes `recordsEvaluated`, `positiveClassCount`, and
`negativeClassCount`. `alertRecommendedPrecision`, `alertRecommendedRecall`, `falsePositiveRate`, and
`falseNegativeRate` are metric availability objects with `available`, `value`, and `reason`; unavailable metrics are not
encoded as fake zeroes. `evaluationPopulation` is required to avoid performance overclaim from
small samples, and it remains diagnostic-only rather than promotion, threshold, or production approval evidence.

The required banner states that shadow performance metrics are offline diagnostics only and are not model promotion
approval, not threshold recommendation, not production decisioning approval, not payment authorization, not automatic
approve/decline/block logic, or not analyst recommendation logic.

## Non-Goals

Shadow Performance Summary v2 is not a dashboard data source contract, not a promotion workflow, not a threshold
workflow, not a model registry write, not a model artifact, not a retraining trigger, not a scoring adapter, not an
alert or fraud-case state mutation, and not a payment authorization path.
