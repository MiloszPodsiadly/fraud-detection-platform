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
policy; the production policy store is therefore empty and assessment fails closed. Synthetic test policies are marked
`NON_INSTITUTIONAL_TEST` and can validate criterion behavior but can never authorize a formal recommendation.

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
the immutable decision ledger, and current `CHAMPION` ownership claims. Registration and transitions require Mongo transactions.
Each transition uses expected state plus expected revision CAS and atomically stores the resulting state, immutable policy
and assessment snapshots, proposal, approval, idempotency outcome and local governance audit evidence. A failed audit or
ledger write rolls back the state and ownership claim.

Idempotency is scoped to the aggregate and operation id. Replays must match canonical command content, actor identity and
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
default production verifier until a trusted authority implementation is configured.

The startup guard requires the shared Mongo transaction runner and capability probe, and verifies the unique identity,
operation, decision and `CHAMPION` indexes. The ledger is append-only through this application boundary; Mongo storage is
not described as WORM or externally immutable, and external audit anchoring remains a separate verified guarantee.
