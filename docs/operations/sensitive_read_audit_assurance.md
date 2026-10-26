# Sensitive read-audit assurance boundary

`read_access_audit_events` stores bounded actor, endpoint, resource, outcome, page/count, and correlation metadata in
MongoDB. For supported synchronous response types, successful reads are persisted before the response body is written.
Fail-closed deployments return a bounded
`503` when mandatory audit persistence or trusted-actor resolution fails. This is durable application-level logging;
it is not an independently witnessed tamper-proof archive.

In fail-closed mode, declared streaming and asynchronous response types are rejected before their handlers run. The
completion interceptor can record an error outcome for a handler that fails, but it cannot retract bytes after an HTTP
response has already been committed; it is not a response-revocation mechanism.

## What the evidence represents

| Evidence | Implemented boundary |
| --- | --- |
| Sensitive read audit | Local MongoDB records in `read_access_audit_events`; bounded metadata, policy-controlled fail-open/closed behavior. No independent chain or anchor for these records. |
| Governance mutation audit | Selected governance business events create transaction-bound commitments and are appended to the local cryptographic audit chain through the outbox. Coverage and recovery are defined in [governance audit commitments](../security/model_governance_audit_commitments.md). |
| Governance external witness | A separately configured witness can support selected governance commitment verification. Its assurance is conditional on the configured witness and independently retained verification material; local fixtures do not establish that assurance. |
| Independent read-audit anchoring | Not implemented. No endpoint or job currently anchors `read_access_audit_events` to the governance mutation chain or to an external witness. |

## Threat and operations matrix

| Event or threat | Current outcome | Limit |
| --- | --- | --- |
| MongoDB unavailable during a sensitive read | Fail-closed mode withholds the response and returns bounded `503`; best-effort mode may return it without a stored event. | Availability and policy configuration determine whether the read is denied. |
| Privileged MongoDB writer changes an event | No independent read-audit detector is provided. | Application-level persistence does not stop a database operator from altering records. |
| Read-audit document deleted | No independent read-audit tombstone or anchor detects deletion. | MongoDB backup/restore and access controls are deployment responsibilities; the repository does not establish their retention guarantees. |
| Event replay or duplicate submission | Each persistence attempt creates a new event ID; there is no independent read-audit replay ledger. | The system does not prove exactly-once logging across client retries or uncertain database acknowledgements. |
| Retention expires | No application-level read-audit retention guarantee is established here. | Confirm deployment retention, backup, restore, and deletion controls separately. |
| Governance external witness unavailable | Governance commitment state can remain pending or unavailable according to its own workflow. | This does not change or anchor sensitive read events. |

Operators should monitor `fraud_platform_read_access_audit_persistence_failures_total` and
`fraud_read_access_audit_actor_missing_total`, validate the selected fail-closed policy at startup, and verify MongoDB
backup and administrative-access controls in the target deployment. The metrics report application observations, not
proof of immutable retention. The local development stack and its MongoDB volume are not a regulator-certified archive.

Independent tamper-resistant read auditing would require a separately scoped threat model, retention and access design,
independent trust operator, and migration/verification plan. It is not part of this closure change.
