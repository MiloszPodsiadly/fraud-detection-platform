# Alert Service Production Runbooks

Each runbook action must be performed by an operator with the documented backend authority. UI visibility is not an authorization boundary.

| Condition | Symptom | Impact | Safe action | Endpoint | Authority | Idempotency | Evidence | Retry | Rollback note | Audit trail | Escalation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| REGULATED_MUTATION_RECOVERY_REQUIRED | Trust level reason code | Mutation needs reconciliation | Inspect command, run recovery | `POST /api/v1/regulated-mutations/recover` | ops admin | N/A | command id/hash | Bounded | No business rollback claim | durable audit/read audit | engineering |
| COMMITTED_DEGRADED | committed degraded count > 0 | Post-commit audit degradation | inspect command and degradation | inspect + degradations | ops admin | N/A | command snapshot | bounded | no FULLY_ANCHORED claim | audit degradation event | security |
| PUBLISH_CONFIRMATION_UNKNOWN | outbox unknown count > 0 | Delivery confirmation ambiguous | inspect outbox, resolve with evidence | `/api/v1/outbox/.../resolve-confirmation` | ops admin | required | broker evidence | manual | no silent publish claim | regulated mutation audit | platform |
| OUTBOX_FAILED_TERMINAL | terminal count > 0 | outbox delivery stopped | repair cause, resolve | outbox recovery | ops admin | required for manual resolution | event id | bounded | alert projection is cache | outbox record | platform |
| OUTBOX_PROJECTION_MISMATCH | projection mismatch count > 0 | Alert cache disagrees | run recovery | `POST /api/v1/outbox/recovery/run` | ops admin | N/A | outbox record | bounded | source is outbox record | metrics/audit | engineering |
| TRUST_INCIDENT_CRITICAL_OPEN | critical incident open | control-plane risk | acknowledge or resolve with evidence | trust incident endpoints | ops admin | required | incident id | manual | no workflow automation | regulated audit | security |
| TRUST_INCIDENT_UNACKNOWLEDGED_CRITICAL | unacknowledged critical count | unowned risk | acknowledge | `/api/v1/trust/incidents/{id}/ack` | ops admin | required | incident id | manual | read endpoints remain read-only | regulated audit | security |
| TRUST_INCIDENT_REFRESH_PARTIAL | refresh partial | local/dev semantics attempted | switch config to ATOMIC | config/startup | operator | N/A | config diff | restart | no partial in bank/prod | startup failure | engineering |
| EVIDENCE_CONFIRMATION_PENDING_TOO_LONG | pending evidence age grows | external evidence delayed | inspect evidence/export | evidence export | audit read/admin | N/A | export fingerprint | bounded | no rollback claim | read audit/export audit | platform |
| EVIDENCE_CONFIRMATION_FAILED | failed evidence count | evidence incomplete | inspect command and anchors | inspection/export | ops admin | N/A | anchor status | bounded | no FULLY_ANCHORED claim | audit degradation | security |
| AUDIT_DEGRADATION_UNRESOLVED | unresolved degradation | audit trust degraded | resolve with verified evidence | audit degradation endpoint | ops admin | body evidence | evidence reference | manual | no hidden repair | durable audit | security |
| TRANSACTION_CAPABILITY_FAILURE | startup fails | bank/prod closed | fix Mongo transaction capability | startup | operator | N/A | startup logs without secrets | restart | no fail-open | startup guard | database |
| SENSITIVE_READ_AUDIT_UNAVAILABLE | sensitive read returns 503 | operational reads blocked | restore audit persistence | affected GET endpoint | audit:read/ops | N/A | stable error code/message | retry after restore | no data exposure | warning metric/log | database |
| EXTERNAL_ANCHOR_GAP | coverage degraded | external evidence lag | run publisher/reconcile | coverage/export | ops admin | N/A | missing ranges | bounded | no best-effort HEAD claim | coverage audit | platform |
| TRUST_AUTHORITY_UNAVAILABLE | trust authority unavailable | signature/attestation degraded | restore authority | trust authority health | operator | N/A | signed status | retry after restore | no local signer in prod claim | metrics/log | security |
