# Shadow Performance Summary Generation Job

Status: FDP-109 local/offline generation foundation.

FDP-109 generates current summary. FDP-108 reads current summary. FDP-106 exposes current summary. FDP-107 displays current summary.

## Scope

FDP-109 is manual/local/offline only. It adds an explicitly invoked local generation command:

```bash
make shadow-performance-summary
```

On Windows, the equivalent manual wrapper is:

```cmd
scripts\shadow-performance-summary.cmd
```

The command builds a candidate `ShadowPerformanceSummary v1` from explicit local/offline inputs and publishes it to:

```text
deployment/local-generated/shadow-performance/current-summary.json
```

The generated file is compatible with the FDP-108 artifact-backed provider when mounted under the configured safe base directory.

## Input And Reuse

The job consumes explicit FDP-102 bounded JSONL export input and model metadata supplied by the caller:

- `deployment/local-demo-inputs/shadow-performance/fdp102-feedback-dataset.synthetic.jsonl`
- `deployment/local-demo-inputs/shadow-performance/model-metadata.synthetic.json`

The local input files are synthetic demo/local inputs only. They are not production data, not current runtime data,
and not exported from real transactions. FDP-109 consumes explicit local/offline FDP-102-format input. FDP-109 does not connect to Mongo/Kafka directly. The generated summary must not contain raw transaction references or evaluation record IDs.

The job reuses the existing governed chain:

- FDP-102 bounded feedback dataset export contract as JSONL input.
- FDP-103 offline evaluation builder for aggregate diagnostic metrics and disagreement counts.
- FDP-104 Model Card v1 builder and writer for validated model governance context.
- FDP-105 Shadow Performance Summary v1 builder and writer for validated current summary output.

It does not duplicate metric calculation logic, does not independently recompute precision, recall, false-positive rate, disagreement counts, or evaluation population, and does not read raw Mongo or Kafka directly.

## Publish Semantics

The job follows a fail-closed publish sequence:

1. Build candidate summary.
2. Serialize deterministic JSON.
3. Write candidate to `current-summary.json.tmp` in the output directory.
4. Parse the temp file.
5. Validate the temp file as `ShadowPerformanceSummary v1`.
6. Atomically move the temp file to `current-summary.json`.

If any step fails, the command exits non-zero, does not overwrite the previous valid `current-summary.json`, does not publish partial output, does not fallback to demo data, and does not write zero metrics.

## Boundary

FDP-109 is not production scheduler. FDP-109 is not promotion readiness. FDP-109 is not threshold recommendation. FDP-109 is not production decisioning. FDP-109 is not payment authorization. FDP-109 is not analyst recommendation logic.

FDP-109 does not mutate model registry state, model artifacts, threshold configuration, online scoring, alert state, fraud-case state, or payment authorization state. It does not emit Kafka events, add a cron job, add a scheduler, expose an API, expose OpenAPI, or add dashboard filters, search, history, charts, model comparison, or UI behavior.

The final generated summary must not contain raw FDP-102 JSONL, raw FDP-103 evaluation report, raw FDP-104 Model Card, per-record examples, raw transaction references or evaluation record IDs, transaction references, evaluation record identifiers, customer/account/card/device/merchant identifiers, analyst identifiers, raw payloads, raw feature vectors, raw ML requests or responses, tokens, secrets, stack traces, endpoints, ground truth, training labels, or final decisions.

The final summary must not contain promotion readiness score, promotion approval, promotion workflow, threshold recommendation, threshold switching, recommended threshold, champion/challenger status, champion candidate, deploy recommendation, production approval, payment authorization, automatic approve/decline/block, or analyst recommendation logic.

Demo fixture metrics remain only for the explicit FDP-108 local demo compose override. FDP-109 does not use `deployment/local-fixtures/shadow-performance/current-summary.demo.json` as generation input or fallback. FDP-110 provides the generated-summary compose override; FDP-109 only generates the artifact.
