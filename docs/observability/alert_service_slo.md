# Alert Service Observability SLOs

Status: current observability source of truth.

## Scope

This document defines operational health signals for `alert-service`. These metrics are used for runtime triage,
trust posture, and incident prioritization. They are not compliance reports, bank certification evidence, production
enablement, or proof of external finality.

Metrics must remain low-cardinality. Do not use actor ids, resource ids, tokens, paths, exception messages, raw
subjects, idempotency keys, lease owners, or command ids as metric labels.

## Critical Signals

| Signal | Threshold | Expected posture | First response |
| --- | --- | --- | --- |
| `regulated_mutation_recovery_required_count` | `> 0` | Trust level degraded. | Inspect regulated mutation recovery backlog. |
| `finalize_recovery_required_count` | `> 0` | Trust level degraded. | Inspect finalize recovery evidence and command state. |
| `outbox_failed_terminal_count` | `> 0` | Trust level degraded. | Follow outbox ambiguity handling. |
| `outbox_projection_mismatch_count` | `> 0` | Trust level degraded. | Compare authoritative outbox record with projection. |
| `outbox_projection_reconciliation_pending_count` | `> 0` beyond one recovery interval | Authoritative projection work remains due, including cases where a mismatch marker could not be persisted. | Run bounded outbox recovery and investigate repeated projection failures. |
| `outbox_recovery_required_count` | `> 0` | Authoritative outbox state requires operator recovery. | Inspect the outbox record and follow bounded recovery guidance. |
| `pending_outbox_resolution_count` | `> 0` | Manual dual-control resolution remains incomplete. | Complete or reject the pending approval with durable evidence. |
| `open_critical_incident_count` | `> 0` | Trust level degraded. | Assign incident owner and keep audit trail. |
| `fraud_platform_read_access_audit_persistence_failures_total` | `> 0` in bank/prod posture | Sensitive reads fail closed. | Verify audit persistence before allowing sensitive read inspection. |

## High Signals

| Signal | Threshold | Expected posture | First response |
| --- | --- | --- | --- |
| Outbox confirmation unknown count | `> 0` | Degraded or attention required. | Inspect confirmation lag and retry state. |
| Evidence confirmation pending count | Sustained above baseline. | Attention required. | Check external dependency and worker throughput. |
| External anchor position lag | Above configured maximum. | Degraded. | Verify anchor publication and manifest read path. |
| Trust incident unacknowledged critical count | `> 0` | Degraded. | Acknowledge or resolve through trust incident workflow. |

## Warning Signals

| Signal | Threshold | Expected posture | First response |
| --- | --- | --- | --- |
| Read audit actor missing | Any sustained increase. | Investigate auth propagation. | Check principal resolution and demo/OIDC mode. |
| Coverage request rate limiting | Sustained non-zero. | Tune clients or limits. | Verify polling behavior and caller identity. |
| Recovery repeated failure count | `> 0` | Investigate strategy. | Inspect bounded recovery result and command state. |

## Interpretation Rules

- Green dashboards indicate current observed posture only.
- Recovery metrics do not prove absence of duplicate broker delivery.
- Audit metrics do not create WORM storage, legal notarization, or bank certification.
- The alert service does not provide exactly-once Kafka delivery or distributed ACID.
- These metrics indicate recovery and trust posture, not absolute absence of duplicate delivery or cross-service
  atomicity.
