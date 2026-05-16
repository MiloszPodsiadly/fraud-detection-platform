# OpenAPI Specification Index

Status: current OpenAPI documentation index.

## Scope

This folder contains hand-maintained OpenAPI 3.0.3 specifications used as public contract references and documentation
test inputs. These files describe API shape and safety posture; they are not code-generation guarantees, runtime
verification by themselves, bank certification, production certification, or external finality evidence.

## Specifications

| Specification | Service | Scope |
| --- | --- | --- |
| [Alert service OpenAPI](alert_service.openapi.yaml) | `alert-service` | Analyst workflows, fraud cases, audit APIs, regulated mutation visibility, governance advisory projection, trust/audit posture APIs. |
| [ML inference service OpenAPI](ml_inference_service.openapi.yaml) | `ml-inference-service` | Scoring, health, Prometheus metrics, model governance, drift, advisory, and internal service-auth contracts. |

## Maintenance Rules

- Keep specs structured YAML, not minified.
- Keep examples bounded and non-sensitive.
- Do not include raw tokens, idempotency keys, actor secrets, stack traces, or dynamic infrastructure identifiers.
- Update [API documentation](../api/index.md) when the contract interpretation changes.
- Update contract tests when a path, schema, or response code is intentionally changed.
