# ML Documentation Index

Status: current ML documentation index.

## Scope

This folder contains current ML runtime, governance, and drift documentation. It describes implemented runtime
visibility and operator interpretation. It does not claim full MLOps automation, model approval, retraining,
production drift decisioning, or model quality certification.

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
