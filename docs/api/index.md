# API Documentation

Status: current API documentation index.

Use this index to choose the narrowest API document for review. API docs are kept separate because they cover
different contracts: endpoint inventory, public status semantics, error envelope, fraud-case lifecycle behavior, and
OpenAPI safety review. Do not merge them into one large API document.

## Reading Order

1. [API surface v1](api_surface_v1.md) for the broad service endpoint inventory.
2. [Public API semantics](public_api_semantics.md) for regulated mutation status interpretation.
3. [API status truth table](status_truth_table.md) when reviewing client behavior for mutation statuses.
4. [API error contract](api_error_contract.md) when reviewing error response shape.
5. [Fraud Case API](fraud_case_api.md) for fraud-case lifecycle and idempotency details.
6. [OpenAPI safety audit](openapi_safety_audit.md) when checking public examples and schema safety.

## API Document Map

| Document | Status | Use for | Do not use for |
| --- | --- | --- | --- |
| [API surface v1](api_surface_v1.md) | Current source of truth | Broad endpoint inventory and compatibility rules across local services | Deep endpoint-specific lifecycle policy |
| [Public API semantics](public_api_semantics.md) | Current source of truth | Regulated mutation response interpretation and non-claims | Replacing OpenAPI schemas or endpoint lists |
| [API status truth table](status_truth_table.md) | Current source of truth | Client-safe interpretation of public regulated mutation statuses | Internal execution-state debugging |
| [API error contract](api_error_contract.md) | Current source of truth | Canonical error envelope: `timestamp`, `status`, `error`, `message`, `details` | Endpoint-specific business response fields |
| [Fraud Case API](fraud_case_api.md) | Current source of truth | Fraud-case lifecycle endpoints, local idempotency, and non-claims | Regulated mutation finality claims |
| [OpenAPI safety audit](openapi_safety_audit.md) | Current audit artifact | Safety posture of `docs/openapi/*.yaml` examples and schemas | Runtime verification or code generation guarantees |
| [Evidence-gated finalize response contract](evidence_gated_finalize_response_contract.md) | Current scoped contract | Feature-flagged submit-decision evidence-gated finalize response shape | General fraud-case lifecycle API behavior |

## Related Specifications

- [Alert service OpenAPI](../openapi/alert_service.openapi.yaml)
- [ML inference service OpenAPI](../openapi/ml_inference_service.openapi.yaml)

## Maintenance Rules

- Keep `api_error_contract.md` aligned with OpenAPI `ApiErrorResponse` and `ErrorResponse` schemas.
- Keep public status wording conservative: local evidence is not external finality.
- Document new endpoint families in `api_surface_v1.md` and the relevant narrow API doc.
- If OpenAPI schema names differ while fields match, record the naming gap in `openapi_safety_audit.md` instead of inventing a new runtime contract.
