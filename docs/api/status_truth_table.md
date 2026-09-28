# API Status Truth Table

Status: current regulated mutation public contract.

| Status | Meaning | Business mutation visible | External confirmation | Client action |
| --- | --- | --- | --- | --- |
| `IN_PROGRESS` | Another worker owns the command. | Not proven | No | Retry the same key later. |
| `RECOVERY_REQUIRED` | Durable state needs recovery inspection. | Unknown | No | Run authorized recovery, then retry the same key. |
| `EVIDENCE_PREPARING` | Required local evidence is being prepared. | No | No | Retry the same key later. |
| `EVIDENCE_PREPARED` | Local preconditions are staged. | No | No | Retry the same key later. |
| `FINALIZING` | Local finalize is in progress. | Not safely reportable | No | Retry the same key; investigate if stale. |
| `FINALIZED_VISIBLE` | Internal repair state mapped conservatively by public APIs. | Yes | No | Monitor or recover. |
| `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | Local finalize and required local evidence exist. | Yes | No | Monitor asynchronous evidence. |
| `FINALIZED_EVIDENCE_CONFIRMED` | Configured evidence policy is satisfied. | Yes | Yes when configured evidence proves it | No retry required. |
| `REJECTED_EVIDENCE_UNAVAILABLE` | Required evidence was unavailable before finalize. | No | No | Restore dependency; follow endpoint retry policy. |
| `FAILED_BUSINESS_VALIDATION` | Business validation rejected the request. | No | No | Correct the request or business state. |
| `FINALIZE_RECOVERY_REQUIRED` | Finalize cannot be reported safely. | Unknown | No | Run authorized recovery. |

Recovery is not success. Finalized pending external is not externally confirmed. Idempotent replay is not distributed
exactly-once processing. None of these statuses proves WORM storage, legal notarization, or distributed ACID.
