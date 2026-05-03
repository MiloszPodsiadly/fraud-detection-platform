# Alert Service Observability SLOs

Metrics are operational health signals, not a compliance report source. Labels must remain low-cardinality: no actor IDs, resource IDs, tokens, paths, exception messages, raw subjects, or idempotency keys.

## Critical

| Signal | Threshold | Expected posture |
| --- | --- | --- |
| `regulated_mutation_recovery_required_count` | > 0 | trust level degraded |
| `committed_degraded_count` | > 0 | trust level degraded |
| `evidence_confirmation_failed_count` | > 0 | trust level degraded |
| `outbox_failed_terminal_count` | > 0 | trust level degraded |
| `outbox_projection_mismatch_count` | > 0 | trust level degraded |
| `open_critical_incident_count` | > 0 | trust level degraded |
| `fraud_platform_read_access_audit_persistence_failures_total` | > 0 in bank/prod | sensitive reads fail closed |

## High

| Signal | Threshold | Expected posture |
| --- | --- | --- |
| outbox confirmation unknown count | > 0 | degraded/attention |
| evidence confirmation pending count | sustained above baseline | attention |
| external anchor position lag | > configured maximum | degraded |
| trust incident unacknowledged critical count | > 0 | degraded |

## Warning

| Signal | Threshold | Expected posture |
| --- | --- | --- |
| read audit actor missing | any sustained increase | investigate auth propagation |
| coverage request rate limiting | sustained non-zero | tune clients or limits |
| recovery repeated failure count | > 0 | investigate strategy |

FDP-27 does not provide exactly-once Kafka delivery or distributed ACID. These metrics indicate recovery and trust posture, not absolute absence of duplicate delivery or cross-service atomicity.
