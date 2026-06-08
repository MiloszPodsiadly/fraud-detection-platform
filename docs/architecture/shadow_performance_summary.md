# Shadow Performance Summary v1

Status: FDP-105 offline diagnostic summary foundation.

## Scope

Shadow Performance Summary v1 is an offline diagnostic artifact. It consumes only validated FDP-104 Model Card v1
objects and caller-provided generation timestamps. It does not recompute metrics, does not read FDP-102 JSONL exports,
does not read FDP-103 raw evaluation reports, and does not inspect per-record data.

Shadow Performance Summary v1 does not approve model promotion, does not recommend thresholds, does not approve
production decisioning, does not authorize payments, does not create automatic approve, decline, or block behavior,
and does not recommend analyst actions. It does not expose API, OpenAPI, UI, or dashboards, and does not create
scheduled jobs, DB writes, Kafka messages, scoring changes, registry writes, or model artifact mutations.

## Input

The only supported source of truth is a validated Model Card v1:

- `cardType = OFFLINE_MODEL_CARD_V1`
- `modelCardVersion = 1.0`
- `governanceStatus = DIAGNOSTIC_ONLY`
- `approvedFor` limited to `SHADOW` and `COMPARE`
- FDP-103 report identity and metric basis already validated by FDP-104
- FDP-102 dataset time basis and deduplication policy already validated by FDP-104

The summary builder copies only allowlisted aggregate and governance fields from the validated model card. It does not
accept raw model cards as output fields and does not pass through raw reports, raw dataset rows, pseudonymous
references, raw payloads, raw feature vectors, identifiers, endpoints, tokens, secrets, exception text, or stack traces.

## Output

The output is deterministic compact JSON with:

- summary type, summary version, and generation timestamp
- bounded model identity
- diagnostic governance and explicit non-goal booleans
- evaluation context inherited from Model Card v1
- aggregate diagnostic metric values inherited from Model Card v1
- rule-vs-ML disagreement summary inherited from Model Card v1
- bounded warnings and limitations
- the required offline diagnostics banner

The required banner states that shadow performance metrics are offline diagnostics only and are not model promotion
approval, threshold recommendation, production decisioning approval, payment authorization, automatic
approve/decline/block logic, or analyst recommendation logic.

## Non-Goals

Shadow Performance Summary v1 is not a dashboard data source contract, not a promotion workflow, not a threshold
workflow, not a model registry write, not a model artifact, not a retraining trigger, not a scoring adapter, not an
alert or fraud-case state mutation, and not a payment authorization path.
