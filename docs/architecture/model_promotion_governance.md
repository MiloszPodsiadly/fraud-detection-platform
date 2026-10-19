# Model Promotion Governance Domain

The Model Promotion domain owns lifecycle meaning for one exact immutable model artifact. The subject identity is
`modelName + modelVersion + featureContractVersion + modelArtifactSha256`; governance scope and intended use are
separate and never alter artifact identity.

The current repository has no authoritative tenant identity boundary. The domain therefore accepts only the explicit
`CURRENT_INSTITUTION` scope and keeps the deployment environment in that scope. This is a deliberate single-
institution limitation, not a claim of multi-tenant isolation.

## Lifecycle

| Source | Allowed targets |
| --- | --- |
| Registration | `CANDIDATE` |
| `CANDIDATE` | `CHALLENGER`, `REJECTED` |
| `CHALLENGER` | `SHADOW_APPROVED`, `REJECTED` |
| `SHADOW_APPROVED` | `PROMOTION_RECOMMENDED`, `REJECTED` |
| `PROMOTION_RECOMMENDED` | `CHAMPION`, `REJECTED` |
| `CHAMPION` | `RETIRED` |
| `RETIRED` | none |
| `REJECTED` | none |

Every transition binds the exact artifact, governance scope, intended use, source state, expected aggregate revision,
target state, operation identity, proposal, accepted decision and approval. Evidence-bound states additionally require
an immutable assessment reference. The transactional persistence boundary verifies the referenced policy, assessment,
evidence digest, proposal and approval before accepting the transition.

`RETIRED` and `REJECTED` are terminal. Reassessment does not reactivate their aggregate; it requires a new attributable
review while retaining the prior decision evidence. Registration creates only `CANDIDATE` at revision zero and is not
approval.

## Exact Evidence Resolution

Promotion evidence is resolved only for the requested immutable model identity and evaluation window. The resolver
requires a verified Model Card and the matching Exact Model Shadow Performance report, binds their actual manifest
digests and shared source-evaluation metadata, and rejects missing, unavailable, crossed-window or inconsistent inputs.
The resulting canonical bundle digest is deterministic; resolution neither evaluates promotion policy nor changes the
promotion lifecycle.

Repository artifact and manifest verification proves only the integrity checks performed by the current readers. The
bundle records that analyst feedback is not certified ground truth, the evaluated population is bounded, and external
attestation, externally immutable storage and promotion-policy freshness are not established. Those limitations cannot
be inferred away from otherwise valid metrics.

## Promotion Policy Assessment

Promotion policy is an immutable, digest-bound contract identified by policy id and revision. It binds scope, intended
use, author and approval provenance, effective interval, evidence freshness, verification level, criterion semantics and
thresholds. The repository does not currently contain an institutionally approved policy authority or active production
policy. A Mongo policy-store implementation exists, but a deployed store is not necessarily empty or populated with
an institutionally approved policy. The default authority verifier returns `UNAVAILABLE`, so an inserted policy alone
cannot authorize formal recommendation. Synthetic test policies are marked
`NON_INSTITUTIONAL_TEST` and can validate criterion behavior but can never authorize a formal recommendation.

### Institutional policy signature input (v1)

`ModelPromotionPolicySignatureContract` defines the bytes to be signed with Ed25519. It uses the existing
`GovernanceCanonicalJson` SHA-256 of `ModelPromotionPolicy.Content`; that digest already covers policy ID/revision,
scope/environment, intended use, effective interval, approval provenance, criteria, thresholds and evidence semantics.
The signature input is a JSON object with exactly these string fields, recursively sorted by field name:
`authorityIdentity`, `contractVersion`, `issuedAt`, `keyId`, `policyCanonicalSha256`, `purpose`.
`contractVersion` is `model-promotion-policy-signature-v1` and `purpose` is
`MODEL_PROMOTION_POLICY_APPROVAL_V1`. The canonical JSON is UTF-8 with exactly one trailing LF byte and no signature
field. Signature bytes are encoded as unpadded base64url; a decoded Ed25519 signature must contain exactly 64 bytes.
Issuer identity and key ID each match `[A-Za-z0-9._:-]{1,128}`.
`issuedAt` must be the canonical UTC `Instant.toString()` representation, with approval time <= issuance time <=
policy effective start. Noncanonical timestamps, malformed signatures and mismatched content digests are rejected.
The policy author, approving authority and signing issuer require distinct identity strings; this structural check
does not establish the number or independence of human approvers.
This purpose is distinct from `AUDIT_ANCHOR`, so a signature over that purpose cannot verify against this input.
The fixed test-only Ed25519 vector and exact input-byte assertion live in `ModelPromotionPolicySignatureContractTest`.

The signer identity authenticates a configured authority only; a valid signature alone does not prove that two
independent humans approved the policy. Controlled publication and approval evidence are separate boundaries.

### Trusted policy issuers

`MODEL_PROMOTION_TRUSTED_ISSUERS_PATH` points to a deployment-controlled, read-only JSON registry file loaded at
`alert-service` startup. With no path, policy authority verification returns `UNAVAILABLE`; an explicitly configured
missing or malformed file fails startup. The file is not a MongoDB policy document and contains public keys only.
Its top-level `version` is `model-promotion-issuer-registry-v1`, `generation` is a positive monotonic integer,
and `keys` is a nonempty array. Each entry requires
`issuerIdentity`, globally unique `keyId`, canonical base64 DER/SPKI `publicKeySpkiBase64`, lowercase SHA-256
`publicKeySha256` over those DER bytes, nonempty `institutionalScopes`, `environments`, and `intendedUses`, canonical
UTC `validFrom` and `validUntil`, and `status` (`ACTIVE`, `RETIRED`, or `REVOKED`). No wildcard permissions are
supported. Currently the only institutional scope and intended use are `CURRENT_INSTITUTION` and
`FRAUD_RISK_SCORING`. For current authorization a key must be `ACTIVE`, authorized for the exact policy
scope/environment/use, and within its validity window both at issuance and at verification time. The policy
interval must be well formed, and an expired policy cannot authorize a new decision. A future-effective policy may
be pre-published; effectiveness at the assessment or decision time is checked by the existing resolver and decision
validator. A retired or revoked key never yields `VERIFIED`. Audit-anchoring keys are not loaded from this registry.

`VERIFIED` requires the exact v1 signing input and a valid Ed25519 signature under the configured public key;
unknown issuers, malformed evidence, mismatched permissions, expired keys and invalid signatures return `REJECTED`.
The resolver and durable decision validator both use this verifier, while no policy publication path is introduced
by the alert-service runtime.

### Key rotation, revocation and trust recovery

The registry is loaded once at service startup; there is no runtime trust reload or mutable trust-management API.
`MODEL_PROMOTION_TRUST_MIN_GENERATION` (default `1`) sets the minimum acceptable registry generation. A configured
file with an older generation, invalid contents or missing file prevents startup; an absent path leaves verification
`UNAVAILABLE`. The generation floor must be raised with each approved trust change and maintained by an independent,
protected deployment control. An attacker able to roll back **both** the registry and this floor can defeat this
application check. The repository cannot itself provide immutable configuration, fleet-wide monotonicity or timely
restart of all service instances.

Use the following controlled sequence, with institutional governance ownership and change records:

1. Generate a new Ed25519 signing key in the production KMS/HSM or equivalent controlled signer. Do not put its
   private key in the registry, MongoDB, `alert-service`, an image, or an audit-anchor key store. Record its globally
   unique `keyId`, issuer identity, SPKI DER public key, SHA-256 fingerprint, exact scope/environment/use permissions
   and bounded validity. Publish the new public-key entry as `ACTIVE` in a higher-generation registry. Verify the
   fingerprint and permissions through an independent deployment review, raise the protected minimum generation,
   then restart all verifier and publisher instances with the same approved trust data.
2. Switch the offline institutional signer to the new key only after the new public key is trusted. The publisher
   still requires independent approval for the exact policy and verifies its own signature against the registry.
   Confirm new policies verify under the new key before retiring the old one; no policy is activated automatically.
3. Stop signing with the previous key and set it to `RETIRED` in a newer registry generation. Set its `validUntil`
   to the last authorized issuance instant and retain its public key for historical checks. Deploy the new generation
   and floor to every instance. `RETIRED` never authorizes a new governance decision, even if a policy carries an
   earlier signed `issuedAt`; a cryptographically intact older policy may be inspected only as history.
4. If a key is compromised, stop its signer, mark it `REVOKED` in a newer generation, raise the protected floor and
   restart every instance promptly. Preserve the revoked public key and all immutable policies and decisions for
   investigation. Identify policies signed by that key, assess exposure and reapprove/reissue any still-needed policy
   using an uncompromised key and independent approval. A valid signature from a revoked key is historical
   cryptographic integrity only, not proof that its claimed issuance time or prior authorization is trustworthy.
   Never delete or rewrite accepted decisions to conceal the incident.
5. If configuration is invalid, keep the affected instance out of service; restore a reviewed registry at or above
   the protected generation floor, verify fingerprints/statuses/permissions against the institutional change record,
   and restart. Do not lower the floor to make an old file start. Check that all instances use the same generation
   before resuming issuance or governance decisions.

The signed `issuedAt` is tamper-evident **only for the key that signed it**; it is not an independent timestamp
authority. A compromised signer can backdate an artifact. Issuance more than 30 seconds ahead of the verifier clock
is rejected; operational clocks and KMS/HSM signing time therefore need controlled synchronization. Historical
inspection checks signature, exact content, permissions and time windows but never grants current authorization;
`INTACT_REVOKED_KEY` explicitly means cryptographic integrity under a now-untrusted key. Current verification
rejects unknown, retired, revoked or expired keys and expired policies. The bounded
`model_promotion_policy_authority_verification_total` metric uses only `status` and enum `reason` tags; it does not
include issuer/key IDs, approval credentials or raw trust configuration. Secure distribution of registry and
minimum-generation settings, KMS/HSM key custody, incident response and institutional approval controls remain
deployment responsibilities.

For the local development stack only, `node scripts/bootstrap-local-model-promotion-keys.mjs --ensure` creates a
separate Ed25519 pair under Git-ignored and Docker-build-excluded
`deployment/.local/model-promotion-policy/`. It writes `private-key.pkcs8.base64` for the offline publisher and
`trusted-issuers.json` containing only the public key and `generation: 1`. The key is named
`local-dev-model-promotion-key-1`, belongs to `local-dev-model-promotion-issuer`, and is authorized only for
`DEVELOPMENT` and `TEST`, never `PRODUCTION`. `make up` and `scripts/app.cmd up` preserve an existing valid pair and
mount only the public registry read-only into `alert-service` through the local Compose overlay. The private key is
not mounted in any runtime service. `--check` validates the pair without generating it; an incomplete or expired
pair fails instead of being silently replaced. Intentional rotation needs a reviewed new key ID, registry generation
and protected floor; do not use fixture regeneration as a production rotation procedure. This local signer does not
provide an institutional approval provider and does not make test policies production-approved.

### Controlled offline publication

`model-promotion-policy-publisher` is a separate Maven executable, not a dependency or Docker image component of
`alert-service`. The publisher reads an unsigned policy (`content` plus `canonicalSha256`), validates its full
contract, and calls an `InstitutionalPolicyPublicationAuthorization` provider. That provider must independently
verify an approval for the exact digest, policy ID/revision, scope, intended use and effective interval, and must
authenticate a publisher with the corresponding institutional mandate. The returned authorization is compared to
every one of those policy fields, the author/approver identities, approval reference, issuer and key ID. Merely
supplying policy JSON, an approver name, an environment variable, a Git signature or `FRAUD_OPS_ADMIN` does not
authorize publication. No trustworthy institutional policy-approval source exists in this repository, so the CLI
requires exactly one separately installed, institution-controlled ServiceLoader provider and otherwise fails closed.
The integration tests use a distinct, signed **test-only** approval fixture; it is not a production provider.

After approval, the offline process loads a private Ed25519 key from a controlled file, signs the v1 envelope,
re-verifies it against the same public-key registry as `alert-service`, then optionally publishes. `--dry-run`
performs all validation and signing checks but never connects to MongoDB. The private key is never placed in the
policy document or the alert-service image. The CLI requires `--policy`, `--private-key`, `--trusted-issuers`,
`--issuer` and `--key-id`; publication additionally requires `MODEL_PROMOTION_PUBLISH_MONGODB_URI` (a replica-set
URI supplied by the controlled execution environment). The unsigned policy must become effective no earlier than
signature issuance.

The publisher uses a mandatory Mongo transaction, independent of alert-service's configurable transaction mode.
Within that transaction it increments one per-scope/environment/use fence document, checks immutable revision
identity and interval overlap, and inserts the signed immutable policy. Competing transactions therefore cannot
both pass the overlap check and commit. The fence is only a concurrency-control record; the existing
`model_promotion_policies` collection remains the authoritative read store. An exact republish returns a receipt
without changing the policy, while conflicting content or an overlapping interval fails closed. Receipts identify
policy ID/revision, canonical digest, issuer, key ID and publication time. Existing policy history is preserved.

| Criterion | Authoritative evidence | Population and denominator |
| --- | --- | --- |
| Statistically meaningful sample | Exact Shadow Performance population and class balance | Evaluated bounded analyst-feedback cohort; total, positive and negative counts |
| Precision at alert-review budget | Matched Rules and exact-ML budget evaluation | Same labeled population and identical requested/actual review budget for both scorers |
| False positive rate | Exact Shadow Performance ML prediction metrics | Analyst-feedback negative class: `FP / (FP + TN)` |
| Rules-vs-ML disagreement | Exact Shadow Performance disagreement metric | Records carrying both exact-ML and Rules evidence |
| Critical segment regressions | Matched segment regression evaluation | Policy-defined identifiable segments evaluated on one matched population |
| Exact-model latency stability | Exact Shadow Performance operational latency | Valid inference observations for the exact artifact and evaluation window |
| Exact-model fallback rate | Exact Shadow Performance operational fallback evidence | Eligible exact-artifact scoring attempts in the evaluation window |
| Complete evaluation provenance | Evidence bundle trust and source evaluation | All mandatory report, manifest, source and window assertions |
| Exact-SHA Model Card | Verified Model Card subject and artifact digest | One Model Card for the assessed immutable artifact |
| Identity and lineage integrity | Exact Shadow Performance population and lineage policy | Source records considered plus every identity/lineage exclusion counter |

Each result is `PASS`, `FAIL`, `INCONCLUSIVE`, `UNAVAILABLE`, or policy-authorized `NOT_APPLICABLE` and records its
source, semantics, population, denominator, verification requirement, freshness bound, observed values and reason.
Unavailable data is never converted to zero or pass. Assessment separately reports diagnostic availability, human
reviewability, formal-recommendation eligibility, governance approval and deployment authorization; it grants neither
approval nor deployment authority and never changes lifecycle state.

The lifecycle gate applies the same assessed criteria with target-specific requirements. A required criterion must be
`PASS`; `NOT_APPLICABLE`, missing, inconclusive and unavailable do not satisfy it.

| Criterion | `SHADOW_APPROVED` | `PROMOTION_RECOMMENDED` | `CHAMPION` |
| --- | --- | --- | --- |
| Complete provenance, exact-SHA Model Card, identity and lineage integrity, meaningful sample | Required | Required | Required |
| Matched Rules-vs-ML precision, false-positive rate, disagreement, critical segments | Diagnostic | Required | Required |
| Exact-model latency and fallback | Diagnostic | Diagnostic | Required |

The shadow gate permits controlled experimentation with incomplete comparison or operational diagnostics, but still
requires a policy-backed sample and verified integrity. Formal recommendation requires an independently verified
institutional policy and all recommendation criteria. `CHAMPION` additionally requires the operational criteria and an
independent approval. Current authoritative evidence does not provide matched precision, critical segments, exact-model
latency or fallback, so those stages remain blocked where those criteria are required. Policy waiver text cannot replace
`PASS` for a target-required criterion; the generic waiver justification is rejected. A verified institutional policy
may describe an exception for a criterion outside that target's mandatory set, but it grants no waiver at a higher gate.

The current Exact Model Shadow Performance contract intentionally reports exact-artifact latency and fallback as
unavailable and contains neither a fair Rules precision-at-budget comparison nor identifiable segment-regression
evidence. Its bounded analyst-feedback labels are review signals, not certified ground truth. These are explicit blockers,
not values to synthesize in Java. Assessment and evidence bundle digests are retained in decision references so later
approval work cannot silently substitute recomputed content.

The state API field `structurallyAllowedTargetStates` reports only edges supported by the lifecycle state machine.
It does not assert current policy/evidence eligibility or caller authorization. The assessment endpoint is a
target-independent diagnostic and formal-recommendation assessment, not a target-specific approval decision.
`formalRecommendationEligible` is separate from independent proposal review, while `governanceApproved` and
`deploymentAuthorized` remain false in assessment responses. A SHADOW proposal can satisfy its narrower gate even
when formal recommendation is blocked; CHAMPION requires its full gate and independent review.

## Authority Boundaries

The Python model registry remains the owner of registered model bytes and immutable model identity. Python lifecycle
history remains operational diagnostics and is not promotion authority. Model Card, Exact Model Shadow Performance and
Promotion Review Readiness remain evidence products and do not own current promotion state. Governance advisory status
is unrelated to model lifecycle.

The domain expresses the one-`CHAMPION` key as governance scope plus intended use. A conditional ownership document,
whose Mongo `_id` is derived only from that key, enforces uniqueness inside the same transaction as the lifecycle CAS.
There is no check-then-save or process-local lock. `CHAMPION` is a governance designation and never authorizes model
deployment, runtime activation, scoring changes, threshold changes or payment decisions.

## Transactional Persistence

The durable boundary uses separate collections for effective policies, current aggregates, pending/reviewed proposals,
the immutable decision ledger, operation-ID claims, and current `CHAMPION` ownership claims. Registration and transitions
require Mongo transactions.
Each transition uses expected state plus expected revision CAS and atomically stores the resulting state, immutable policy
and assessment snapshots, proposal, approval, idempotency outcome and local governance audit evidence. A failed audit or
ledger write rolls back the state and ownership claim.

Idempotency is scoped to the aggregate and operation id across registration, proposal submission and proposal review.
A Mongo operation claim with deterministic `_id` reserves that namespace in the same transaction as the mutation;
separate unique indexes on the decision and proposal collections alone cannot enforce cross-collection uniqueness.
Existing decisions and proposals without claims remain replayable, and new claims also check those legacy records so
their operation IDs cannot be reused for a different mutation. Already-committed legacy cross-collection collisions
cannot be retroactively repaired by this check and require an explicit data audit before migration.
Replays must match canonical command content, actor identity and
the caller's authorization fingerprint. The proposal stores a separate stable request-intent digest over exact artifact,
scope, intended use, source state and revision, target, evaluation window, proposal and operation identifiers, actor,
and validated rationale. After authorization, this digest is checked against committed Mongo state before policy or
evidence is resolved; a new operation still fails closed if a dependency is unavailable. The original command digest
continues to bind the policy and assessment snapshots accepted on the first commit. Committed responses are reconstructed
after restart or a lost client response. Modified or unauthorized replays fail closed. History reads are bounded for resource control, but the
bound is not a retention policy and does not delete historical decisions.

Accepted lifecycle decisions have unique `decision_id` values. Rejection events have their own unique operation key
and no `decision_id`; they preserve the prior accepted decision identity, state and revision. Proposal status, rejection
or acceptance ledger event, local audit evidence and any state or champion-claim mutation commit in one Mongo transaction.
Emergency retirement can proceed without active promotion evidence and atomically releases its champion claim.

General model-promotion reads expose lifecycle and decision metadata plus rationale references and SHA-256 values, but
omit original rationale text. Reviewers and explicitly designated governance/audit readers need the separate
`MODEL_PROMOTION_RATIONALE_READ` authority and the requested environment scope to receive rationale content through the
audited state read. Mutation and retry responses do not echo rationale content. The repository has no automatic rationale
retention or deletion policy; access to the underlying Mongo collections must remain restricted operationally.

The API rate limiter is process-local, bounded by tracked actors and request windows, and synchronizes concurrent access.
At capacity it rejects untracked actors until an expired window is removed. Replicas do not share counters; ingress
rate limiting is still needed for a fleet-wide bound. Institutional policy authorization remains unavailable in the
default production verifier until a trusted authority implementation is configured. Existing audit-signature machinery
does not establish authorization of the exact promotion-policy digest by an approved issuer and key for the requested
scope and effective interval. It is not treated as a policy-authority adapter; provenance strings and unsigned Mongo
documents cannot turn `UNAVAILABLE` into `VERIFIED`.

`MODEL_PROMOTION_PERSISTENCE_ENABLED` defaults to `true`. This flag controls the additional model-promotion startup
verification, not persistence availability: even when set to `false`, the index initializer still connects to the
configured MongoDB and creates or checks the required indexes, and model-promotion reads and writes remain available.
It must not be used to disable Mongo or bypass a conflicting index definition; a malformed existing definition causes
bootstrap to fail. Keep the flag enabled in production. With verification enabled, the startup guard requires the
shared Mongo transaction runner and capability probe and verifies the identity, operation, decision, and `CHAMPION`
indexes. Spring Data's global Mongo auto-index setting does not duplicate these definitions because the promotion
documents do not declare annotation-based indexes. The ledger is append-only through this application boundary; Mongo
storage is not described as WORM or externally immutable, and external audit anchoring remains a separate verified
guarantee.
