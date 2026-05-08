# OpenAPI Safety Audit

Status: current audit of public API specification safety.

## Scope

Audited files:

- `docs/openapi/alert-service.openapi.yaml`
- `docs/openapi/ml-inference-service.openapi.yaml`

## Findings

| Area | Status | Notes |
| --- | --- | --- |
| Unsafe production claims | Pass with caveats | Specs explicitly do not claim WORM, legal proof, distributed ACID, or exactly-once delivery. |
| Sensitive examples | Pass with caveats | Examples should stay sanitized and avoid raw tokens, raw hashes, raw lease owners, and stack traces. |
| Public statuses | Pass | Alert service spec lists regulated mutation public statuses and conservative descriptions. |
| Error schemas | Needs normalization | Both specs define error responses, but names differ between services. |
| Security schemes | Pass | Bearer/demo or service-auth schemes are present where applicable. |
| Idempotency | Pass for regulated mutation endpoints | Submit-decision and recovery-style endpoints document `X-Idempotency-Key` where required. |
| Rate limits | Partial | Sensitive ops endpoints document bounded/rate-limited behavior, but gateway-level production enforcement remains external. |
| Audit notes | Pass | Recovery, inspection, audit, and trust endpoints document audit behavior and limitations. |

## Required Safety Rules

- Do not include stack traces, raw exception class names, raw tokens, raw hashes, raw lease owners, or internal paths
  in public examples.
- Do not describe local commit, signed release artifacts, or audit evidence as bank certification.
- Do not describe outbox/Kafka behavior as exactly-once delivery.
- Use `ErrorResponse` style fields consistently: `code`, `message`, `correlationId`, `timestamp`, `details`.

## Recommended Follow-Up

Normalize `ApiErrorResponse` and `ErrorResponse` naming in a future API-contract branch if code generation starts
depending on a single shared schema. This cleanup records the gap and guards public examples; it does not change runtime
API contracts.
