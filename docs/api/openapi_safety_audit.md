# OpenAPI Safety Audit

Status: current audit of public API specification safety.

## Scope

Audited files:

- `docs/openapi/alert_service.openapi.yaml`
- `docs/openapi/ml_inference_service.openapi.yaml`

## Findings

| Area | Status | Notes |
| --- | --- | --- |
| Unsafe production claims | Pass with caveats | Specs explicitly do not claim WORM, legal proof, distributed ACID, or exactly-once delivery. |
| Sensitive examples | Pass with caveats | Examples should stay sanitized and avoid raw tokens, raw hashes, raw lease owners, and stack traces. |
| Public statuses | Pass | Alert service spec lists regulated mutation public statuses and conservative descriptions. |
| Error schemas | Pass with naming caveat | Both specs use the `timestamp/status/error/message/details` envelope, but schema names differ between services. |
| Security schemes | Pass | Bearer/demo or service-auth schemes are present where applicable. |
| Idempotency | Pass for regulated mutation endpoints | Submit-decision and recovery-style endpoints document `X-Idempotency-Key` where required. |
| Rate limits | Partial | Sensitive ops endpoints document bounded/rate-limited behavior, but gateway-level production enforcement remains external. |
| Audit notes | Pass | Recovery, inspection, audit, and trust endpoints document audit behavior and limitations. |

## Required Safety Rules

- Do not include stack traces, raw exception class names, raw tokens, raw hashes, raw lease owners, or internal paths
  in public examples.
- Do not describe local commit, signed release artifacts, or audit evidence as bank certification.
- Do not describe outbox/Kafka behavior as exactly-once delivery.
- Use the shared platform error envelope fields consistently: `timestamp`, `status`, `error`, `message`, `details`.

## Recommended Follow-Up

Normalize `ApiErrorResponse` and `ErrorResponse` schema names in a future API-contract branch if code generation
starts depending on a single shared schema name. The field-level envelope already matches; this cleanup records the
naming gap and guards public examples without changing runtime API contracts.
