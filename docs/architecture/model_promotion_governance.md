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
thresholds. The repository contains a first-party, independently signed author/approver/publisher publication path and
an Ed25519 issuer verifier, but it does **not** provision production credentials or an active production policy.
The v2 offline review input is `ModelPromotionPolicyDraft`: its versioned canonical SHA-256 covers all reviewable
fields plus intended author, approver, publisher and issuer identities/keys, but excludes the not-yet-known
`approvedAt`. The author signs that draft digest. The approver's separate v2 Ed25519 decision signs the author
statement hash, decision, reference and the CLI clock time sampled when the action is accepted. The publisher derives
the final `ModelPromotionPolicy.Content` from that frozen draft and decision time, recomputes its distinct final SHA-256,
revalidates the proof and only then issues the unchanged v1 policy signature. The draft digest does not depend on the
final policy digest, so there is no circular hash. The offline operator clock is not an independently attested backend
commit time; the future online workflow must record its own trusted acceptance time and action evidence atomically.
Without a configured public issuer registry the verifier returns `UNAVAILABLE`; an inserted Mongo policy alone cannot
authorize a formal recommendation. Synthetic policies marked `NON_INSTITUTIONAL_TEST` cannot authorize **any**
production quality-gated promotion, including `SHADOW_APPROVED`. The local Compose registry contains only
development/test keys. See [trust operations](../operations/model_promotion_trust_operations.md).

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

The policy-trust API records signed maker and independent reviewer decisions for exact candidate files; approval
does not install a file. Compose declares the expected alert verifier and publisher identities for a rollout.
Each process reports the trust loaded into its verifier or publisher, while deployment diagnostics also compare
the API's configured file. Missing, stale, duplicated or mismatched observations prevent an observed-consistent
status, and the publisher pauses new signatures while its declared fleet disagrees. Mongo heartbeats and the
declared inventory are not independent proof that every capable process was discovered or activated; the system
does not report `ACTIVATION_CONFIRMED`. See [trust operations](../operations/model_promotion_trust_operations.md).

Use the following controlled sequence, with institutional governance ownership and change records:

1. Generate a new Ed25519 signing key under separately controlled offline custody (a future KMS/HSM integration is
   optional, not part of this implementation). Do not put its
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
is rejected; operational clocks and offline signing time therefore need controlled synchronization. Historical
inspection checks signature, exact content, permissions and time windows but never grants current authorization;
`INTACT_REVOKED_KEY` explicitly means cryptographic integrity under a now-untrusted key. Current verification
rejects unknown, retired, revoked or expired keys and expired policies. The bounded
`model_promotion_policy_authority_verification_total` metric uses only `status` and enum `reason` tags; it does not
include issuer/key IDs, approval credentials or raw trust configuration. Secure distribution of registry and
minimum-generation settings, private-key custody, incident response and institutional approval controls remain
deployment responsibilities.

For the local development stack only, `node scripts/bootstrap-local-model-promotion-keys.mjs --ensure` creates a
separate Ed25519 pair under Git-ignored and Docker-build-excluded
`deployment/.local/model-promotion-policy/`. It writes `private-key.pkcs8.base64` for the offline issuer and
`trusted-issuers.json` containing only the public key and `generation: 1`. The key is named
`local-dev-model-promotion-key-1`, belongs to `local-dev-model-promotion-issuer`, and is authorized only for
`DEVELOPMENT` and `TEST`, never `PRODUCTION`. `make up` and `scripts/app.cmd up` preserve an existing valid pair and
mount only the public registry read-only into `alert-service` through the local Compose overlay. The private key is
not mounted in any runtime service. `--check` validates the pair without generating it; an incomplete or expired
pair fails instead of being silently replaced. The same bootstrap creates a separate `approval/` directory with
author A, approver B and publisher private keys and `trusted-approvals.json` public mandates; the issuer also has
an explicit `POLICY_ISSUER` mandate there. These additional private keys are not mounted in `alert-service` and
normal startup creates no decision or approved policy. Intentional rotation needs a reviewed new key ID, registry generation
and protected floor; do not use fixture regeneration as a production rotation procedure. This local signer alone does
not establish independent institutional approval or make test policies production-approved.

### Internal independent approval proof (v2)

`InternalPolicyApprovalArtifact` is one versioned proof containing two separately signed statements. Its
`AuthorStatement` includes contract version `model-promotion-internal-approval-v2`, purpose
`MODEL_PROMOTION_POLICY_DRAFT_AUTHOR_V2`, exact policy ID/revision and canonical draft SHA-256, scope/environment,
intended use, effective interval, approval reference, author identity/key ID, designated publisher identity/key ID,
issuer identity/key ID and `authoredAt`. The author signs its recursively field-sorted canonical JSON UTF-8 bytes
with one trailing LF.
The `ApprovalDecision` includes the same contract version, distinct purpose
`MODEL_PROMOTION_POLICY_DRAFT_APPROVER_V2`, SHA-256 of the exact author-signing bytes, approver identity/key ID, explicit
`APPROVED` or `REJECTED`, `decidedAt`, `expiresAt` and approval reference. The independent approver signs that
statement with the same canonical byte encoding. Both Ed25519 signatures use canonical unpadded base64url. The
version and purpose are part of the signed bytes; neither signature can be substituted for the policy-issuer
signature or the other actor's signature.

`InternalPolicyApprovalTrustRegistry` is a separate deployment-controlled public-key JSON registry, version
`model-promotion-approval-trust-v1`, with a positive `generation`, unique key IDs, canonical Ed25519 SPKI public keys
and SHA-256 fingerprints, exact institutional scope/environment/intended-use permissions, validity windows,
`ACTIVE`/`RETIRED`/`REVOKED` status and explicit `POLICY_AUTHOR`, `POLICY_APPROVER`, `POLICY_PUBLISHER`,
`POLICY_ISSUER` capabilities. A minimum generation supplied by protected deployment configuration prevents loading
an older registry unless that external floor is also rolled back. No private key or approval authority is inferred
from a policy, Mongo document, CLI identity string or `FRAUD_OPS_ADMIN` role. Only an operator controlling the
deployment registry can grant or revoke these mandates.

The first-party verifier reconstructs the exact draft from the final policy and signed intended publisher/issuer,
recomputes its SHA-256, matches both statements and final provenance, verifies
both actor signatures, rejects self-approval and reuse of the same physical author/approver key, checks role and
validity at signing and verification time, and rejects expired approval or non-`APPROVED` decisions. The executing
publisher proves control of its separately mandated private key by signing a fresh random challenge containing
purpose `MODEL_PROMOTION_POLICY_PUBLISHER_CHALLENGE_V1`, contract version, proof SHA-256 and nonce. The matching
trusted public key verifies that response; a claimed publisher identity in JSON alone is insufficient. Issuer
identity/key must have an explicit issuer mandate and the subsequent policy signature must also verify under the
separate policy-issuer registry. Signed timestamps are tamper-evident but not independent timestamp-authority proof;
private-key custody, actor identity enrollment and registry/floor protection remain operator responsibilities.

The offline CLI now uses this first-party verifier directly; no separately installed ServiceLoader provider or
external JAR is needed. `author` creates a signed author submission, `approve` creates an independently signed
`APPROVED` or `REJECTED` artifact, and `publish` authenticates its publisher credential before policy signing.
The exact local commands and review hand-off are in the
[publication procedure](../operations/model_promotion_policy_publication.md).

### Controlled offline publication

`model-promotion-policy-publisher` is a separate Maven executable, not a dependency or Docker image component of
`alert-service`. The publisher reads a frozen draft (`content` plus `canonicalSha256`), validates its full
contract, derives final content using the accepted decision time, and calls the repository's
`InternalPolicyPublicationAuthorization` implementation. It must independently verify an approval for the exact
draft and derived policy digests, policy ID/revision, scope, intended use and effective interval, and must
authenticate a publisher with the corresponding institutional mandate. The returned authorization is compared to
every one of those policy fields, the author/approver identities, approval reference, issuer and key ID. Merely
supplying policy JSON, an approver name, an environment variable, a Git signature or `FRAUD_OPS_ADMIN` does not
authorize publication. The CLI's actor identity strings select public mandates; each actor must prove possession of
the matching Ed25519 private key. Its output files are created without overwriting existing files. Tests exercise
the first-party approval implementation as well as earlier isolated publication fixtures.

After approval, the offline process loads a private Ed25519 key from a controlled file, signs the v1 envelope,
re-verifies it against the same public-key registry as `alert-service`, then optionally publishes. `--dry-run`
performs all validation and signing checks but never connects to MongoDB. The private key is never placed in the
policy document or the alert-service image. `publish` requires the frozen draft, signed approval artifact,
separate approval and issuer public registries, publisher credential and issuer private key. Live publication
additionally requires `MODEL_PROMOTION_PUBLISH_MONGODB_URI` (a replica-set URI supplied by the controlled execution
environment). The final policy must become effective no earlier than signature issuance.

The publisher uses a mandatory Mongo transaction, independent of alert-service's configurable transaction mode.
Within that transaction it increments one per-scope/environment/use fence document, checks immutable revision
identity and interval overlap, and inserts the signed immutable policy together with a distinct approval-evidence
document holding the original proof and digest, actor/key fingerprints, publisher role and authorized scope/use,
approval-registry generation and canonical registry digest. The deployment must preserve that registry version for
independent historical mandate review. Competing transactions therefore cannot
both pass the overlap check and commit. The fence is only a concurrency-control record; the existing
`model_promotion_policies` collection remains the authoritative read store; the evidence collection is not another
policy authority. A repeat with the same content and original approval proof returns a receipt without changing
either document (even if a fresh signature would have a new issuance time). Different proof/content or an overlapping
interval fails closed. Receipts identify policy ID/revision, canonical digest, issuer, key ID and publication time.
The intended publisher exposes no update/delete evidence path. Direct Mongo access is a separate operational trust
boundary, and this application-level immutability is not WORM storage.

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

Current producer availability (the Python exact-model report is read only through verified manifest-bound artifacts;
`ModelPromotionEvidenceResolver` never creates missing measurements):

| Criterion | Source and exact artifact | Trusted output now | Missing requirement / lineage and denominator | Required gate | Can produce now? |
| --- | --- | --- | --- | --- | --- |
| Meaningful sample | Python Exact Model Shadow Performance report for exact SHA/window | Bounded evaluated count and both feedback-label class counts | Policy minimums; evaluated population and positive/negative counts | Shadow+ | Conditional on real cohort size |
| Precision at review budget | Exact report `mlPredictionMetrics.precisionAtK` | ML-only requested/actual K and precision | Rules precision on the **same** labeled rows and identical budget; matched denominator | Recommendation+ | No |
| False-positive rate | Exact report `mlPredictionMetrics` | FP, TN and rate on bounded feedback cohort | Adequate negatives, `FP/(FP+TN)` and source-label lineage | Recommendation+ | Conditional |
| Rules-vs-ML disagreement | Exact report `rulesVsMlDisagreement` | Differing-classification categories and compared count | Sufficient records with both exact-ML and Rules evidence; `recordsCompared` | Recommendation+ | Conditional |
| Critical segment regressions | No matched segment-regression artifact | None | Policy-defined segment identity, matched population, segments evaluated and breach count | Recommendation+ | No |
| Exact-model latency stability | Exact report `operationalDiagnostics.mlLatencyP95` | Explicit `UNAVAILABLE` diagnostic | Exact-artifact inference observations in window, p95, sample size and time basis | Champion | No |
| Exact-model fallback rate | Exact report `operationalDiagnostics.fallbackRate` | Explicit `UNAVAILABLE` diagnostic | Exact-artifact fallback numerator and eligible scoring-attempt denominator in window | Champion | No |
| Complete provenance | Verified Model Card + exact report and manifests | Shared source manifest, verification level, generated time and window | All mandatory source/manifest equality checks; no external attestation claim | Shadow+ | Conditional on matching verified artifacts |
| Exact-SHA Model Card | Verified Model Card artifact | Subject SHA and verified manifest | Exact model bytes and one matching subject/artifact SHA | Shadow+ | Conditional on a real card |
| Identity and lineage integrity | Exact report `population` + `lineagePolicy` | Considered/evaluated rows and exclusion counters | No critical unresolved identity or lineage gaps; source records considered | Shadow+ | Conditional |

`Shadow+` means `SHADOW_APPROVED`, `PROMOTION_RECOMMENDED`, and `CHAMPION`; `Recommendation+` means the latter two.
The conditional rows can still fail policy thresholds or artifact checks. No existing trustworthy producer output
supports a small safe binding for the four `No` rows, so this prompt adds no synthetic metric adapter or ML engine.

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

Concurrent reviews of one proposal use the proposal-status CAS within that transaction. A losing review with a
Mongo `TransientTransactionError` retries the complete transaction with bounded backoff, re-reading the durable
proposal on each attempt. Once another review commits, the loser receives a stable domain conflict; an identical
operation instead receives an idempotent replay. `UnknownTransactionCommitResult` is different: the service does not
start a second decision, but performs bounded primary reads for the committed outcome and returns unavailable if it
cannot establish one. Exhausted transient retries also return unavailable while the proposal is still pending.

Accepted lifecycle decisions have unique `decision_id` values. Rejection events have their own unique operation key
and no `decision_id`; they preserve the prior accepted decision identity, state and revision. Proposal status, rejection
or acceptance ledger event, local audit evidence and any state or champion-claim mutation commit in one Mongo transaction.
Emergency retirement can proceed without active promotion evidence and atomically releases its champion claim.

General model-promotion reads expose lifecycle and decision metadata plus rationale references and SHA-256 values, but
omit original rationale text. Reviewers and explicitly designated governance/audit readers need the separate
`MODEL_PROMOTION_RATIONALE_READ` authority and the requested environment scope to receive rationale content through the
audited state read. Mutation and retry responses do not echo rationale content. The repository has no automatic rationale
retention or deletion policy; access to the underlying Mongo collections must remain restricted operationally.

Model-promotion API rate limiting uses the shared Redis deployment by default. A single atomic Lua sliding-window
operation enforces one per-actor budget across alert-service replicas. Redis keeps only SHA-256-derived actor keys,
expires request windows and the bounded active-actor index automatically, and uses a fixed `UNAVAILABLE` result
when Redis is down or the actor index reaches capacity; the controllers return `503` for that result and `429` only
for an exhausted actor quota. In JWT/OIDC mode, the key is derived from the trusted issuer and subject, never from a
request parameter. Explicit local/demo authentication uses a separate namespaced hash and grants no institutional
identity. Set `MODEL_PROMOTION_API_RATE_LIMITER_MODE=in-memory` only for an explicitly single-node local
development run; it is not an availability fallback. The state, assessment, lifecycle, signed-review, and emergency
retirement HTTP endpoints all enforce this shared limiter before invoking their workflow. Production ingress may add
a broader network-level limit as defense in depth, but must preserve authentication and must not expose service
methods through an alternate route.

The declared Compose topology is one standalone Redis instance. Redis Cluster is **UNVERIFIED and unsupported** for
this limiter: its Lua operation accesses an actor-specific sorted-set key and a shared actor-index key without a
common hash tag, so Cluster may reject the script with `CROSSSLOT`. Configure production with standalone or
cluster-mode-disabled Redis until a slot-safe implementation has a real Redis Cluster integration proof. The
limiter fails closed if the configured Redis rejects the script.
The first-party verifier authorizes only signatures under a deployment-provisioned, current public issuer registry;
with no configured registry it remains `UNAVAILABLE`.
Existing audit-signature keys are not a policy-authority adapter. Provenance strings and unsigned Mongo documents
cannot turn `UNAVAILABLE` into `VERIFIED`. The loaded issuer generation is visible as the bounded,
label-free `model_promotion_policy_trust_registry_generation` gauge; operators must also compare the mounted file
fingerprint because generation alone does not establish identical bytes across replicas.

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

Audit-outbox recovery exposes separate assurance fields: `localReceiptStatus`, `receiptIntegrityStatus`,
`referencedEvidenceStatus` and `independentWitnessStatus`. Recovery receipts and their canonical SHA-256 digest are stored
in the same MongoDB administrative boundary as the outbox; a valid digest detects accidental or partial changes but a
privileged MongoDB administrator could rewrite both the receipt and digest. The recovery receipt is therefore not WORM,
tamperproof or independently notarized. Existing external audit-anchor verification applies to the referenced governance
append and does not bind the recovery-receipt digest. The service reports `NOT_INDEPENDENTLY_ANCHORED` for that receipt;
an unavailable referenced-evidence verifier reports `NOT_VERIFIED`. Institutional independent assurance requires an
operator-provisioned immutable external store or witness under a separate administrative boundary, a verifier that binds
the exact receipt digest, documented retention and key custody, and a readiness/preflight check before policy can claim
that assurance. No such receipt witness is currently configured.

## Durable policy-governance workflow

`alert-service` owns a separate `modelpolicy` application context for draft, submission, independent review and publication request. The Mongo proposal, operation receipt and append-only local history commit atomically with CAS. This does not add another policy evaluator or signed-policy store. Explicit `model-policy:*` IAM groups are separate from analyst roles; production additionally requires `model-policy:scope:production`. Submission and review require an OIDC login session, the matching explicit mandate and a fresh WebAuthn assertion over a domain-separated immutable draft/action contract. The registered public credential, one-use challenge, assertion evidence and backend acceptance time are handled transactionally with the decision; an opaque legacy approval reference cannot authorize a publication request. The authenticator does not sign the later backend acceptance timestamp, and neither `APPROVED` nor `PUBLICATION_PENDING` means that a policy is signed, published or already `VERIFIED`.

The normal publication boundary is the isolated publisher worker. The online request freezes the approved draft and creates a unique durable job atomically with `PUBLICATION_PENDING`. The worker claims a bounded Mongo lease, independently replays the retained WebAuthn proofs against public credentials and current actor mandates, proves publisher-key control, and checks the current issuer mandate. It then signs the exact approved content with the separate Ed25519 issuer key and uses the existing transactional publication fence for immutable policy plus approval evidence. A second transaction acknowledges the job and proposal as `PUBLISHED`; a restarted worker reconciles an already committed policy/evidence pair before retrying. The scoped publication-status API reports job state, issuer signature and independent approval-evidence verification separately. New institutional policy reads and model decisions require both checks to pass under current mandates; issuer `VERIFIED` alone never grants `FULLY_AUTHORIZED`. The reader mounts only public issuer, approval and actor registries; only the publisher process receives private publisher and issuer keys. The local Compose worker shares the demo Mongo replica set and therefore does not establish production-grade database-credential isolation.
