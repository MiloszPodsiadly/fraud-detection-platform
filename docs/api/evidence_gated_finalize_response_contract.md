# Evidence-Gated Finalize Response Contract

This is the current regulated mutation response contract. No endpoint returns an updated business resource unless the
durable state proves local finalize.

| Status | HTTP class | Updated resource | Required interpretation |
| --- | --- | --- | --- |
| `IN_PROGRESS` | 202 | No | Command is owned; retry the same key. |
| `EVIDENCE_PREPARING` | 202 | No | No visible business mutation. |
| `EVIDENCE_PREPARED` | 202 | No | Preconditions are staged, not finalized. |
| `FINALIZING` | 202 | No | Finalize is not safely reportable as success. |
| `RECOVERY_REQUIRED` | 202 | No | Authorized recovery must inspect durable state. |
| `FINALIZE_RECOVERY_REQUIRED` | 202 | No unless recovered evidence proves a stable snapshot | Never infer success from a stale snapshot. |
| `FINALIZED_VISIBLE` | 200 | Yes | Internal repair state; public mapping remains conservative. |
| `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | 200 | Yes | Local finalize succeeded; external evidence is pending. |
| `FINALIZED_EVIDENCE_CONFIRMED` | 200 | Yes | Configured evidence policy is explicitly satisfied. |
| `REJECTED_EVIDENCE_UNAVAILABLE` | Endpoint policy | No | Required evidence was unavailable before finalize. |
| `FAILED_BUSINESS_VALIDATION` | 4xx | No | Correct the request or business state. |

Finalized responses require a stable response snapshot for replay. Matching replay must not duplicate business
mutation, local success audit, or outbox records. External evidence is never inferred from absence of errors.
