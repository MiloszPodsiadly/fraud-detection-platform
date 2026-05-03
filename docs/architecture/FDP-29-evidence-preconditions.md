# FDP-29 Evidence Preconditions

This document defines the future preconditions that must be satisfied before a regulated mutation may enter `FINALIZING`.

Do not require Kafka publish before finalize. Kafka publication is a downstream outbox effect after the local finalize transaction.

| Precondition | Bank Mode | Non-Bank Mode | Source of Truth | Failure Behavior | Retry Behavior | Boundary |
| --- | --- | --- | --- | --- | --- | --- |
| Durable regulated command exists | Required | Required | `regulated_mutation_commands` | No mutation; return pending/rejected status if persistence fails | Safe retry may create or read same command by idempotency key | Local ACID |
| Idempotency key accepted and conflict-free | Required | Required | Command idempotency hash and canonical intent hash | Conflict returns conflict status; no mutation | Same key/same payload replays state; different payload/actor rejected | Local ACID |
| `ATTEMPTED` audit phase recorded | Required | Required for regulated writes | Durable audit store | `REJECTED_EVIDENCE_UNAVAILABLE`; no visible business mutation | Retry may attempt evidence preparation again if command proves no finalize happened | Local durable evidence |
| `ATTEMPTED` external anchoring status known if required | Required when external evidence required | Optional or best-effort by mode | External publication status index plus local audit chain | Reject or degrade before finalize according to policy; never guess success | Retry evidence preparation; no hidden background success assumption | External/eventual |
| Audit phase deterministic key reserved | Required | Required | Deterministic audit request id, for example `commandId:ATTEMPTED|SUCCESS` | Reject before visible mutation if key cannot be reserved | Same deterministic key may be retried idempotently | Local ACID |
| Transaction capability healthy | Required | Optional by mode, but explicit | Mongo transaction capability probe | Startup or command rejection in bank mode | Retry only after capability restored | Local runtime capability |
| Transactional outbox can be written | Required for mutation that emits events | Required for event-emitting mutation | `transactional_outbox_records` | Reject before visible mutation if outbox write cannot participate in local transaction | Retry command if state proves no finalize happened | Local ACID |
| Trust Authority signing available if required | Required when signing policy requires it | Optional by mode | Trust Authority client and local verification status | Reject before visible mutation when required signing is unavailable | Retry evidence preparation; do not log token/key material | Remote/external unless local signer |
| External anchor sink available if required | Required when external anchoring is mandatory | Optional/degraded by mode | External anchor client/status repository | Reject before visible mutation if required; otherwise mark pending external | Retry external publication separately | External/eventual |
| Business validation passed | Required | Required | Domain validator and current aggregate state | `FAILED_BUSINESS_VALIDATION`; no visible mutation | Same key/same payload replays validation failure unless business state changed by separate command | Local domain logic |
| Actor authorization resolved and stable | Required | Required | Backend authentication/authorization context | Reject before command finalization; no frontend-derived authority | Same key/different actor rejected | Security boundary |
| Canonical intent hash stored | Required | Required | Command document | Reject or recovery-required if intent cannot be persisted | Same payload must produce same hash; different hash conflicts | Local ACID |
| Recovery strategy registered | Required | Required for supported regulated command | Recovery strategy registry | Reject before accepting unsupported regulated command | Retry after deployment/config fix only if command has not finalized | Runtime configuration |
| Sensitive read/write audit policy available if required | Required in bank/prod policy | Optional according to mode | Audit policy configuration | Reject/fail closed before sensitive result or regulated visible mutation | Retry after policy/audit availability restored | Local policy plus durable audit |

## Finalize Gate Rule

`EVIDENCE_PREPARED` means every required local precondition has a durable local proof. It does not mean external witness confirmation, Kafka broker delivery, or legal proof has completed.

`FINALIZING` may start only from `EVIDENCE_PREPARED`.

`FINALIZED_VISIBLE` may be persisted only inside the local Mongo transaction that applies the business aggregate mutation, writes the transactional outbox record when required, stores the response snapshot, and stores the local finalize marker.
