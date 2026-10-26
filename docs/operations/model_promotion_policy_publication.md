# Local internal policy approval and publication

The normal local path is the online governance workflow in `alert-service`, followed by the isolated
`model-promotion-policy-publisher` worker. `make up` creates local keys and starts that worker, but creates no
policy or approval decision. A policy author and a different policy approver must each complete a fresh WebAuthn
ceremony through the [governance API](model_policy_governance_workflow.md). A separately authorized requester then
creates a durable publication request. The worker verifies the frozen draft, public credential assertions, current
actor mandates and publisher/issuer keys independently before signing and atomically publishing the policy and its
approval evidence. `GET /api/v1/governance/model-policies/{id}/publication` shows job state and the current policy
signature result; `PENDING`, `CLAIMED`, `RETRYABLE` and `BLOCKED` do not imply `VERIFIED`. Lease takeover and immutable-receipt
reconciliation handle process restarts and uncertain commit acknowledgement. The local worker uses the shared Mongo
replica set; production needs separately scoped database credentials and independently managed trust material.
The `PUBLISH_POLICY` history event records the publication transition and approval evidence reference. Its
`rationale` is null because publication is not a new human review decision; the signed review rationale belongs
to the separate `REVIEW_APPROVE` history event.

## Publication recovery state machine

| From | Condition | To | Operator meaning |
| --- | --- | --- | --- |
| PENDING | Worker atomically claims the frozen request | CLAIMED | One lease and attempt number exist. |
| CLAIMED | Operational failure before commit, with attempts 1–4 | RETRYABLE | Worker may claim another attempt. |
| RETRYABLE | No commit proven; worker claims within the authorized budget | CLAIMED | Prior attempts remain in the ledger. |
| RETRYABLE | Exact immutable commit proven | CLAIMED, then PUBLISHED | Reconciliation consumes no new signing attempt. |
| CLAIMED | Exact policy and evidence commit, followed by atomic job, proposal and audit acknowledgement | PUBLISHED | Authoritative publication is complete. |
| CLAIMED | Lost commit acknowledgement or failed durable read | CLAIMED | Outcome is uncertain; wait for lease expiry and reconcile durable data. |
| CLAIMED | Expired final attempt with no matching commit, invalid current trust, immutable conflict, or three failed reconciliation cycles | BLOCKED | No further automatic signing or reconciliation. |
| BLOCKED | Independent operator requests recovery for `ATTEMPTS_EXHAUSTED` | RETRYABLE | Exactly one extra attempt is authorized; at most three recoveries. |
| BLOCKED or stale CLAIMED/RETRYABLE | Independent operator verifies an exact committed publication | PUBLISHED | Job, proposal, attempt and history are acknowledged atomically; no new signature or recovery allowance. |

The normal budget is five attempts, plus one for each authorized recovery, capped at eight total. A recovery
operation ID replays only for the same actor, proposal, request and draft; each accepted request has one durable
audit event. An expired final lease is first taken for reconciliation **without** consuming another attempt.
`IMMUTABLE_CONFLICT` and `CURRENT_TRUST_OR_EVIDENCE_INVALID` remain blocked. Operational failures within the
budget are retryable; an unknown commit result remains uncertain until the original policy revision and exact
approval proof can be read. A matching committed revision is acknowledged without signing again. The signed policy,
approval evidence and lease fence write share one Mongo transaction; job acknowledgement, proposal transition and
`PUBLISH_POLICY` audit share another. Three failed lease reconciliation cycles end in
`RECONCILIATION_UNAVAILABLE`; its durable claim predicate prevents further automatic loops if the final block write fails. An independent operator can use the [committed-publication reconciliation](model_policy_publication_recovery.md) route after the database is restored and the exact immutable commit verifies.
Operators can inspect classified commit outcomes, failure codes and attempt/recovery history in the
governance UI; only the isolated worker holds the issuer private key. A `PUBLISHED` job still requires current
`FULLY_AUTHORIZED` verification for model decisions.

In the local Compose stack, a network-isolated, one-shot key loader copies the publisher's two policy signing keys,
its separate fleet reporting key and four public trust registries into a dedicated Docker volume. It sets UID/GID
`10001:10001` and file mode `0400`; the non-root publisher mounts that volume read-only. The host bootstrap rejects
missing, malformed or symlinked local credentials. `alert-service` receives the public registries and only its own
reporting key through a separate protected volume. Docker Desktop may display Windows bind
sources as `0777` inside the short-lived loader, so the protected volume—not bind-file mode—is the publisher's
runtime boundary. The fixture is local-only and does not replace production secret management.
The fleet evidence boundary and local MongoDB privilege limits are described in
[fleet admission](../security/model_promotion_fleet_admission.md).

The commands below are a controlled **offline emergency/fixture path** for `DEVELOPMENT`/`TEST`, not the normal
online approval route. Its author and approver must be different people controlling different private keys.
The demo key files are **not production credentials**. Run from the repository root; Java 21, Maven, Node.js and a
running local Docker Compose Mongo replica set are prerequisites for live publication. The launcher builds an
executable JAR with `-exec.jar`; the plain `.jar` is not executable.
For provisioning, rotation, revocation, protected generation floors and recovery outside this fixture, use
[model-promotion trust operations](model_promotion_trust_operations.md). `make model-promotion-trust-check` (or
`.\scripts\model-promotion-trust-check.ps1` on Windows) checks the local public registries without issuing an approval
or publishing a policy.

The versioned draft SHA-256 contains all reviewable policy semantics and intended publisher/issuer identities, but
**no** `approvedAt`. B reviews that exact draft; the offline CLI samples its own UTC clock when B accepts or rejects,
and B's Ed25519 decision signs that actual sampled time. Publication derives final content from the frozen draft and
signed decision, then computes a different final policy SHA-256 for the issuer signature. The effective start is the
approval/publication deadline, not a scheduled approval timestamp. Actor-signed times are claims by the offline
operator host, not independently certified timestamps; the separate online governance workflow records its decision time from the backend clock.
Outputs use `CREATE_NEW` so existing proofs cannot be overwritten. Old v1 pending approval proofs are not accepted
under the v2 draft contract and must be re-authored and re-approved; already published v1 policy signatures retain
their existing verification contract and immutable Mongo history.

## PowerShell (Windows)

```powershell
node scripts/bootstrap-local-model-promotion-keys.mjs --ensure
mvn -pl model-promotion-policy-publisher -am -DskipTests package
$jar = 'model-promotion-policy-publisher/target/model-promotion-policy-publisher-0.0.1-SNAPSHOT-exec.jar'
$fixture = 'deployment/.local/model-promotion-policy'
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$run = "$fixture/review-$stamp"
New-Item -ItemType Directory -Path $run | Out-Null
$effectiveFrom = (Get-Date).ToUniversalTime().AddDays(1).ToString('yyyy-MM-ddTHH:mm:ssZ')
$expiresAt = (Get-Date).ToUniversalTime().AddDays(7).ToString('yyyy-MM-ddTHH:mm:ssZ')
java -jar $jar demo-draft --effective-from $effectiveFrom --policy-id "local-demo-$stamp" --approval-reference "review-$stamp" --out "$run/policy.json"
java -jar $jar author --policy "$run/policy.json" --approval-trust "$fixture/approval/trusted-approvals.json" --author-private-key "$fixture/approval/author-a.private-key.pkcs8.base64" --author-identity local-dev-policy-author-a --author-key-id local-dev-author-a-key-1 --out "$run/author.json"
# B: inspect the frozen draft, author submission and SHA-256; choose APPROVED or REJECTED before $effectiveFrom.
java -jar $jar approve --submission "$run/author.json" --approval-trust "$fixture/approval/trusted-approvals.json" --approver-private-key "$fixture/approval/approver-b.private-key.pkcs8.base64" --approver-identity local-dev-policy-approver-b --approver-key-id local-dev-approver-b-key-1 --decision APPROVED --expires-at $expiresAt --out "$run/decision.json"
java -jar $jar publish --policy "$run/policy.json" --approval "$run/decision.json" --approval-trust "$fixture/approval/trusted-approvals.json" --trusted-issuers "$fixture/trusted-issuers.json" --publisher-private-key "$fixture/approval/publisher.private-key.pkcs8.base64" --issuer-private-key "$fixture/private-key.pkcs8.base64" --dry-run
$env:MODEL_PROMOTION_PUBLISH_MONGODB_URI = 'mongodb://127.0.0.1:27017/fraud_alerts?directConnection=true&replicaSet=rs0'
java -jar $jar publish --policy "$run/policy.json" --approval "$run/decision.json" --approval-trust "$fixture/approval/trusted-approvals.json" --trusted-issuers "$fixture/trusted-issuers.json" --publisher-private-key "$fixture/approval/publisher.private-key.pkcs8.base64" --issuer-private-key "$fixture/private-key.pkcs8.base64"
```

## Bash (Linux)

```bash
node scripts/bootstrap-local-model-promotion-keys.mjs --ensure
mvn -pl model-promotion-policy-publisher -am -DskipTests package
jar=model-promotion-policy-publisher/target/model-promotion-policy-publisher-0.0.1-SNAPSHOT-exec.jar
fixture=deployment/.local/model-promotion-policy
stamp=$(date -u +%Y%m%d%H%M%S)
run="$fixture/review-$stamp"
mkdir "$run"
effective_from=$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)
expires_at=$(date -u -d '+7 days' +%Y-%m-%dT%H:%M:%SZ)
java -jar "$jar" demo-draft --effective-from "$effective_from" --policy-id "local-demo-$stamp" --approval-reference "review-$stamp" --out "$run/policy.json"
java -jar "$jar" author --policy "$run/policy.json" --approval-trust "$fixture/approval/trusted-approvals.json" --author-private-key "$fixture/approval/author-a.private-key.pkcs8.base64" --author-identity local-dev-policy-author-a --author-key-id local-dev-author-a-key-1 --out "$run/author.json"
# B: inspect the exact frozen draft and digest; choose APPROVED or REJECTED before effective_from.
java -jar "$jar" approve --submission "$run/author.json" --approval-trust "$fixture/approval/trusted-approvals.json" --approver-private-key "$fixture/approval/approver-b.private-key.pkcs8.base64" --approver-identity local-dev-policy-approver-b --approver-key-id local-dev-approver-b-key-1 --decision APPROVED --expires-at "$expires_at" --out "$run/decision.json"
java -jar "$jar" publish --policy "$run/policy.json" --approval "$run/decision.json" --approval-trust "$fixture/approval/trusted-approvals.json" --trusted-issuers "$fixture/trusted-issuers.json" --publisher-private-key "$fixture/approval/publisher.private-key.pkcs8.base64" --issuer-private-key "$fixture/private-key.pkcs8.base64" --dry-run
MODEL_PROMOTION_PUBLISH_MONGODB_URI='mongodb://127.0.0.1:27017/fraud_alerts?directConnection=true&replicaSet=rs0' java -jar "$jar" publish --policy "$run/policy.json" --approval "$run/decision.json" --approval-trust "$fixture/approval/trusted-approvals.json" --trusted-issuers "$fixture/trusted-issuers.json" --publisher-private-key "$fixture/approval/publisher.private-key.pkcs8.base64" --issuer-private-key "$fixture/private-key.pkcs8.base64"
```

For a real policy instead of a demo draft, author a `ModelPromotionPolicyDraft` JSON and run
`java -jar <exec-jar> prepare --content <draft-content.json> --out <draft.json>`. The draft includes all criterion
rules, scope, revision, author and intended approver, publisher and issuer key IDs, approval reference and effective
interval, but no approval time. Substitute that draft path in the author and publish commands. Any `REJECTED`,
tampered, expired, unknown-key or self-approved proof is refused by the publisher.

The publication transaction binds the signed policy to the original proof and its SHA-256 in
`model_promotion_policy_approval_evidence`. It also stores approving and publisher key IDs, issuer/key, signed
policy signature and publication time. Both it and `model_promotion_policies` are retrievable for audit. An exact
repeat keeps the original publication and proof; changed proof/content conflicts. The intended publisher has no
update/delete evidence command. Restrict direct Mongo privileges and backups separately: application immutability
is not independently operated WORM storage. `MODEL_PROMOTION_APPROVAL_TRUST_MIN_GENERATION` and
`MODEL_PROMOTION_TRUST_MIN_GENERATION` are separately protected registry floors, defaulting to `1` only locally.

After publication, `VERIFIED` reports only the current issuer signature. New institutional policy reads and model
decisions require `FULLY_AUTHORIZED`: the exact stored policy, issuer signature and separately stored approval proof
must all verify against current public issuer, approval and (for online proofs) actor mandates. Missing or changed
evidence and revoked authority block new decisions; they do not rewrite historical model decisions. The publication
status API reports signature and approval-evidence results separately. Log in to Model Promotion as model-governance
actor C, register the exact artifact SHA and submit a
`CANDIDATE → CHALLENGER` proposal; a distinct authorized reviewer D must approve it. The committed model decision is
separate from the policy approval. The runnable
`ModelPromotionPolicyPublisherMongoIntegrationTest#realApprovedSignedPolicyAndIndependentModelReviewCommitThroughOneMongoBoundary`
exercises that entire path with real test keys, Spring Security actors and Mongo transactions. It also confirms a
higher quality-gated state stays blocked without exact evidence. `VERIFIED` is not `CHAMPION`, and `CHAMPION` is not
runtime deployment authorization.
