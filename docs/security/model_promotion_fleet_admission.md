# Model promotion fleet admission

The publication worker requires three separate conditions before a new signature: exact current policy and approval verification, current loaded issuer/actor/approval trust with rollback floors, and consistent reports from the declared alert verifiers and publisher. The fleet condition is an availability gate. It does not replace the worker's signing authorization checks.

## Threat and privilege boundary

| Actor | Capability in the local stack | Limit of the control |
| --- | --- | --- |
| Ordinary OIDC user | Cannot write the observation collection through the application API. | Cannot request activation through a generic HTTP endpoint. |
| Governance user with trust review authority | Can approve an exact candidate through the independent review flow. | Approval leaves the candidate pending operator installation; it does not alter a mounted registry or confirm activation. |
| Compromised alert-service | Can use its own observation key and its broad application Mongo connection. | Cannot sign a publisher report without that workload's separate key. Its report is still a claim by the compromised process. |
| Compromised publisher | Can use its own observation key and, if signing credentials are compromised, attempt publication. | The alert observation key is in another volume; mandatory policy, approval and issuer checks remain in the worker. |
| Mongo collection writer | Can insert, delete, replace and replay stored reports. | Cannot create a fresh valid report for a role/instance without its observation private key. Deletion and replay within the 90 second freshness window remain possible. |
| Mounted registry file modifier | Can change bytes visible on disk. | An already running reporter describes startup-loaded trust, so a file change without reload is not a matching report. A restart under a malicious host-provided trust file is outside this proof. |
| Infrastructure operator | Can install an approved registry through the protected installer and control local deployment membership and key mounts. | The installer validates approved exact bytes, generation, digest and rollback floor; an operator-controlled host can still subvert local runtime evidence. |
| Host/root administrator | Can read or replace local files, process memory, keys and containers. | The local key scheme cannot defend against host/root compromise. |

Local Compose starts MongoDB without authentication. Its `mongodb://mongodb:27017/fraud_alerts` application connections therefore do **not** have per-collection or per-instance Mongo roles. Any backend workload reaching Mongo can write the observation collection. The application does not claim that Mongo enforces reporter identity or least-privilege collection ACLs. Production deployment needs authenticated Mongo users and narrowly scoped roles; this local stack's signature check protects a positive fleet decision against a Mongo-only writer, but it does not prevent denial of service.

## Evidence and admission

- **OBSERVATIONAL_CONSISTENCY:** declared instances report fresh, exact in-memory registry generations and canonical digests. Missing or stale expected instances, duplicate live identities, shared live process epochs, fresh unexpected instances, wrong digest, rollback and truncated observations prevent consistency.
- **AUTHENTICATED_WORKLOAD_EVIDENCE:** each report has an Ed25519 signature over a domain-separated, length-prefixed payload binding document ID, deployment, role, instance, process epoch and start time, registry, generation, digest, observation time and key ID. The publisher verifies these reports against a startup-pinned public registry before using fleet consistency as a gate. An unsigned legacy report cannot satisfy that gate.
- **SIGNING_AUTHORIZATION:** the worker independently checks actual loaded trust, keys, policy bytes and independent approval evidence before signing. A matching report alone cannot authorize a signature.

The local fixture bootstrap creates separate alert and publisher observation keys under `deployment/.local/model-promotion-policy/fleet-observation/`, outside Git. Dedicated one-shot loaders copy each private key into a separate volume owned by the workload UID. Only the public key registry is shared. The reporter holds the signing key and loaded trust snapshot in memory; the guard holds the public registry in memory. For another deployment or more replicas, provision a distinct key per declared instance, update the public registry and deployment membership together, and distribute each private key only to its bound workload. Do not copy a local fixture key into production.

Settings displays telemetry consistency separately from `AUTHENTICATED_WORKLOAD_EVIDENCE`, and `activationConfirmed` stays false. A valid workload signature proves possession of its configured key, not hardware attestation, a complete independent instance inventory or operator-confirmed activation. A compromised signed workload can lie about its own state; a Mongo writer can replay a still-fresh genuine report. The protected installer and external infrastructure controls remain responsible for deployment and key custody.
