# Runbook Index And Standards

Status: current runbook standard.

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

## Priority Runbooks

- Stale or expired regulated mutation lease: `fdp-33-lease-renewal-runbook.md`
- Checkpoint renewal failure: `fdp-34-safe-checkpoint-renewal-runbook.md`
- Recovery required: `fdp-29-finalize-recovery-required.md`
- Production readiness recovery drill: `fdp-35-regulated-mutation-recovery-drill-runbook.md`
- Real chaos recovery drill: `fdp-36-real-chaos-recovery-drill-runbook.md`
- Alert-service production operations: `alert-service-production-runbooks.md`

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
