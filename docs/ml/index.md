# ML Documentation Index

Status: current ML documentation index.

## Scope

This folder contains current ML runtime, governance, and drift documentation. It describes implemented runtime
visibility and operator interpretation. It does not claim full MLOps automation, model approval, an automated retraining workflow,
production drift decisioning, or model quality certification.

The repository includes an offline `evaluate_challenger_diagnostics()` helper that may train a challenger for a
bounded held-out comparison. Its outcomes are limited to `BETTER_ON_OBSERVED_METRICS`, `REQUIRES_SHADOW_REVIEW`,
`NOT_BETTER_ON_OBSERVED_METRICS`, and `INSUFFICIENT_EVIDENCE`. The result records sample size, dataset provenance,
evaluation windows, observed metrics, configured comparison bounds, and limitations. It does not approve promotion,
recommend a rollout mode, mutate the model registry, deploy a model, change scoring mode, or grant production-primary
decision authority. Analyst feedback remains an evaluation signal rather than certified fraud ground truth.

## Current Sources

| Document | Scope |
| --- | --- |
| [ML governance and drift v1](ml_governance_drift_v1.md) | Runtime model metadata, aggregate reference/inference profiles, drift checks, advisory events, metrics, privacy policy, and incident playbook. |

## Related Documents

- [ML inference service OpenAPI](../openapi/ml_inference_service.openapi.yaml)
- [Operations and observability v2](../observability/operations_observability_v2.md)
- [API surface v1](../api/api_surface_v1.md)

## Interpretation Rules

- Governance and drift are advisory runtime visibility, not automated model control.
- Drift output does not change fraud scores, alert thresholds, Java fallback behavior, or analyst decisions.
- The bundled reference profile is synthetic/local unless replaced by an approved production-quality baseline.
- Metrics must remain low-cardinality and must not include raw features, identifiers, paths, exception text, or payloads.
