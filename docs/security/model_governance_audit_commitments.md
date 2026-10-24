# Institutional model governance audit commitments

The alert service writes an immutable business event and a `governance-event-commitment-v1` obligation in the same Mongo transaction. The commitment binds the event kind and content, institutional scope, actor, object, operation, proof digest, result, and time. The existing audit outbox later appends the commitment hash to the audit chain using a deterministic request ID. An acknowledgement lost after append is reconciled against that exact audit event. There is no distributed transaction with the audit authority or external witness.

Covered events include credential grant approval and revocation, emergency retirement request/authorization/consumption/revocation, trust-change request and review, policy governance decisions, publication, recovery, and reconciliation. Failed or rejected decisions are retained when their business service persists an immutable event. A pre-transaction validation failure has no committed business event to bind.

`GET /api/v1/governance/audit/commitments/{kind}/{eventId}` requires both `audit:read` and production governance authority. It returns digests and status, not credential material or full proofs. `PENDING`, `RETRYABLE`, and `BLOCKED` describe the outbox. `MISSING_EVIDENCE` and `TAMPERED` signal absent or inconsistent local material. `LOCAL_VERIFIED_EXTERNAL_UNCONFIRMED` means the business event, obligation, audit event, chain position, and local anchor agree. `LOCAL_VERIFIED_SIGNED_ENFORCED_WITNESS` additionally means the configured witness has enforced retention and the existing audit authority validated its signature. The separate `independentAnchorStatus` field reports witness and signature detail. Historical WebAuthn proof verification is performed for reviewed grants and trust changes; `DIGEST_BOUND_NOT_REVERIFIED` explicitly marks other event kinds.

| Threat | Detection and limit |
| --- | --- |
| Accidental event or outbox corruption | Commitment recomputation or missing-obligation result. |
| Application mutation of an existing event | Original chain receipt disagrees with the recomputed commitment. |
| Privileged Mongo edit, deletion, or partial rollback | Per-event reads detect missing records, broken links, replayed positions, and inconsistent local head/count. A coherent rollback of Mongo and all local records requires a separately retained witness to expose it. |
| Audit authority compromise | Signature verification alone cannot establish independence from the issuer. Rotate and retain trusted public keys; investigate affected attestations. |
| Mongo and audit authority compromise | Requires independent, retained witness material and an independent verifier with a pinned trust root. This stack does not claim that assurance. |

The local development stack generates the audit authority Ed25519 key once under ignored `deployment/.local/audit-trust-authority`. A one-shot loader copies it into a protected Docker volume for UID 10001; the authority mounts that volume read-only. Keep a protected backup of the local directory to verify historical signatures after host loss. Its storage and the local-file witness are not WORM, legal notarization, or an independent verifier. The audit API deliberately does not return an `EXTERNAL_INDEPENDENTLY_VERIFIED` conclusion from the issuing authority's own verification endpoint.
