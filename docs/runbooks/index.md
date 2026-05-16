# Runbook Index

Status: current runbook standard.

## Current Runbooks

| Runbook | Use when |
| --- | --- |
| [Alert service operations](alert_service_operations.md) | Runtime operations, trust incidents, outbox ambiguity, audit degradation, and startup closure. |
| [Regulated mutation recovery](regulated_mutation_recovery.md) | Lease renewal failures, checkpoint renewal failures, finalize recovery, and local audit-chain contention. |
| [Regulated mutation drills](regulated_mutation_drills.md) | Modeled recovery drills and real alert-service kill-restart drill evidence. |
| [Fraud case operations](fraud_case_operations.md) | Fraud-case lifecycle idempotency, work queue cursor rotation, and sensitive-read audit failures. |

## Required Sections

Professional operator runbooks should contain:

1. Purpose
2. Scope
3. Symptoms
4. Impact
5. Safe operator actions
6. Forbidden actions
7. Required authority or role
8. Audit requirement
9. Rollback or retry guidance
10. Example output
11. Escalation
12. Non-claims

## Ops Endpoint Rules

- Ops endpoints are admin-only.
- Sensitive reads are audited.
- Rate limiting is required before production enablement.
- Audit failure must fail closed for sensitive ops reads in production-like posture.
- Response fields must be operator-safe and redacted.
- Ops endpoint access is not business approval.
- Ops inspection is not production enablement.
- Ops recovery visibility is not external finality.

## Safe Response Example

```json
{
  "commandId": "cmd_example_redacted",
  "idempotencyKeyHash": "req_hash_redacted_example",
  "leaseOwner": "worker_redacted",
  "state": "RECOVERY_REQUIRED",
  "auditStatus": "AUDITED",
  "correlationId": "corr_example"
}
```

## Forbidden Raw Fields Example

Do not put these values in public docs, tickets, dashboards, or examples:

```json
{
  "idempotencyKey": "<redacted>",
  "requestHash": "<redacted>",
  "leaseOwner": "<redacted>",
  "stackTrace": "<redacted>",
  "token": "<redacted>"
}
```

## Standard Error Examples

```json
{
  "code": "FORBIDDEN",
  "message": "Required authority is missing.",
  "correlationId": "corr_example",
  "timestamp": "2026-05-08T00:00:00Z",
  "details": []
}
```

```json
{
  "code": "RATE_LIMITED",
  "message": "Retry after the configured operator-safe interval.",
  "correlationId": "corr_example",
  "timestamp": "2026-05-08T00:00:00Z",
  "details": []
}
```

## Non-Claims

Runbook visibility is not production enablement, bank certification, external finality, or legal evidence.
