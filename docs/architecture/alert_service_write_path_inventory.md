# Alert Service Write Path Inventory

| Path | Actor | Idempotency | Coordinator | Audit | Recovery | Source of truth | Bank/prod behavior | Bound |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Submit analyst decision | analyst | Required | RegulatedMutationCoordinator | ATTEMPTED/SUCCESS phase audit | Regulated mutation recovery | RegulatedMutationCommand + AlertDocument | Transaction mode REQUIRED | Single command |
| Trust incident refresh | ops admin | Required | RegulatedMutationCoordinator | Regulated mutation phase audit | Recovery strategies | TrustIncidentDocument | ATOMIC refresh only | Signal set bounded by collector |
| Trust incident ACK | ops admin | Required | RegulatedMutationCoordinator | Regulated mutation phase audit | Recovery strategies | TrustIncidentDocument | No success audit before state save | Single incident |
| Trust incident RESOLVE | ops admin | Required | RegulatedMutationCoordinator | Regulated mutation phase audit | Recovery strategies | TrustIncidentDocument | No success audit before state save | Single incident |
| Outbox confirmation resolution | ops admin | Required | RegulatedMutationCoordinator | Regulated mutation phase audit | Outbox recovery | transactional_outbox_records | Dual control required | Single outbox record |
| Audit degradation resolution | ops admin | Body evidence | AuditDegradationService | Durable audit | Manual review | AuditDegradationEventDocument | Resolution tracked; no rollback claim | Single degradation |
| Outbox recovery run | system/admin | Not applicable | OutboxRecoveryService | Metrics/state transitions | Retry/reconciliation | transactional_outbox_records | Publisher and recovery enabled | Top 100 per repair step |
| Regulated mutation recovery run | system/admin | Not applicable | RegulatedMutationRecoveryService | Recovery audit phases | Strategy-specific | RegulatedMutationCommand | Recovery enabled | Top 100 per query |
| External audit publication | system | Not applicable | External publisher | Publication status | Retry/reconciliation | external publication status | Local/noop sinks rejected when prod-like publication enabled | Configured publish limit |

Sensitive operational reads are audited through `SensitiveReadAuditService`. In bank/prod they fail closed when read-audit persistence is unavailable.
