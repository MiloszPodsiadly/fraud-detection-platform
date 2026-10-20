# Local internal policy approval and publication

This is an explicit offline workflow for the `DEVELOPMENT`/`TEST` local trust fixture. `make up` creates keys, not
policies or approval decisions. The author and approver must be different people controlling different private keys.
The demo key files are **not production credentials**. Run from the repository root; Java 21, Maven, Node.js and a
running local Docker Compose Mongo replica set are prerequisites for live publication. The launcher builds an
executable JAR with `-exec.jar`; the plain `.jar` is not executable.
For provisioning, rotation, revocation, protected generation floors and recovery outside this fixture, use
[model-promotion trust operations](model_promotion_trust_operations.md). `make model-promotion-trust-check` (or
`.\scripts\model-promotion-trust-check.ps1` on Windows) checks the local public registries without issuing an approval
or publishing a policy.

`approvedAt` is part of the exact policy digest. Choose a future review time before the author signs, and have B
review the signed author submission and make the real decision at or after that time. The CLI refuses early
`APPROVED` decisions; it never chooses `APPROVED` for B. A `REJECTED` decision can be made immediately. Actor-signed
times are claims, not independently certified timestamps. If the planned time or any policy field must change,
prepare and author-sign a new exact digest. Outputs use `CREATE_NEW` so existing proofs cannot be overwritten by
these commands.

## PowerShell (Windows)

```powershell
node scripts/bootstrap-local-model-promotion-keys.mjs --ensure
mvn -pl model-promotion-policy-publisher -am -DskipTests package
$jar = 'model-promotion-policy-publisher/target/model-promotion-policy-publisher-0.0.1-SNAPSHOT-exec.jar'
$fixture = 'deployment/.local/model-promotion-policy'
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$run = "$fixture/review-$stamp"
New-Item -ItemType Directory -Path $run | Out-Null
$reviewAt = (Get-Date).ToUniversalTime().AddMinutes(15).ToString('yyyy-MM-ddTHH:mm:ssZ')
$expiresAt = (Get-Date).ToUniversalTime().AddDays(7).ToString('yyyy-MM-ddTHH:mm:ssZ')
java -jar $jar demo-draft --review-at $reviewAt --policy-id "local-demo-$stamp" --approval-reference "review-$stamp" --out "$run/policy.json"
java -jar $jar author --policy "$run/policy.json" --approval-trust "$fixture/approval/trusted-approvals.json" --author-private-key "$fixture/approval/author-a.private-key.pkcs8.base64" --author-identity local-dev-policy-author-a --author-key-id local-dev-author-a-key-1 --publisher-identity local-dev-policy-publisher --publisher-key-id local-dev-publisher-key-1 --issuer local-dev-model-promotion-issuer --key-id local-dev-model-promotion-key-1 --out "$run/author.json"
# B: inspect policy.json, author.json and the printed SHA-256. Wait until $reviewAt; choose APPROVED or REJECTED.
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
review_at=$(date -u -d '+15 minutes' +%Y-%m-%dT%H:%M:%SZ)
expires_at=$(date -u -d '+7 days' +%Y-%m-%dT%H:%M:%SZ)
java -jar "$jar" demo-draft --review-at "$review_at" --policy-id "local-demo-$stamp" --approval-reference "review-$stamp" --out "$run/policy.json"
java -jar "$jar" author --policy "$run/policy.json" --approval-trust "$fixture/approval/trusted-approvals.json" --author-private-key "$fixture/approval/author-a.private-key.pkcs8.base64" --author-identity local-dev-policy-author-a --author-key-id local-dev-author-a-key-1 --publisher-identity local-dev-policy-publisher --publisher-key-id local-dev-publisher-key-1 --issuer local-dev-model-promotion-issuer --key-id local-dev-model-promotion-key-1 --out "$run/author.json"
# B: inspect the exact content and digest; at or after review_at choose APPROVED or REJECTED.
java -jar "$jar" approve --submission "$run/author.json" --approval-trust "$fixture/approval/trusted-approvals.json" --approver-private-key "$fixture/approval/approver-b.private-key.pkcs8.base64" --approver-identity local-dev-policy-approver-b --approver-key-id local-dev-approver-b-key-1 --decision APPROVED --expires-at "$expires_at" --out "$run/decision.json"
java -jar "$jar" publish --policy "$run/policy.json" --approval "$run/decision.json" --approval-trust "$fixture/approval/trusted-approvals.json" --trusted-issuers "$fixture/trusted-issuers.json" --publisher-private-key "$fixture/approval/publisher.private-key.pkcs8.base64" --issuer-private-key "$fixture/private-key.pkcs8.base64" --dry-run
MODEL_PROMOTION_PUBLISH_MONGODB_URI='mongodb://127.0.0.1:27017/fraud_alerts?directConnection=true&replicaSet=rs0' java -jar "$jar" publish --policy "$run/policy.json" --approval "$run/decision.json" --approval-trust "$fixture/approval/trusted-approvals.json" --trusted-issuers "$fixture/trusted-issuers.json" --publisher-private-key "$fixture/approval/publisher.private-key.pkcs8.base64" --issuer-private-key "$fixture/private-key.pkcs8.base64"
```

For a real policy instead of a demo draft, author a `ModelPromotionPolicy.Content` JSON and run
`java -jar <exec-jar> prepare --content <content.json> --out <unsigned-policy.json>`. This validates the full
content and computes its canonical SHA-256; then substitute that unsigned-policy path in the author and publish
commands. Any `REJECTED`, tampered, expired, unknown-key or self-approved proof is refused by the publisher.

The publication transaction binds the signed policy to the original proof and its SHA-256 in
`model_promotion_policy_approval_evidence`. It also stores approving and publisher key IDs, issuer/key, signed
policy signature and publication time. Both it and `model_promotion_policies` are retrievable for audit. An exact
repeat keeps the original publication and proof; changed proof/content conflicts. The intended publisher has no
update/delete evidence command. Restrict direct Mongo privileges and backups separately: application immutability
is not independently operated WORM storage. `MODEL_PROMOTION_APPROVAL_TRUST_MIN_GENERATION` and
`MODEL_PROMOTION_TRUST_MIN_GENERATION` are separately protected registry floors, defaulting to `1` only locally.

After publication, the production verifier can report the signature as `VERIFIED` only for a matching active trusted
issuer and scope. Log in to Model Promotion as model-governance actor C, register the exact artifact SHA and submit a
`CANDIDATE → CHALLENGER` proposal; a distinct authorized reviewer D must approve it. The committed model decision is
separate from the policy approval. The runnable
`ModelPromotionPolicyPublisherMongoIntegrationTest#realApprovedSignedPolicyAndIndependentModelReviewCommitThroughOneMongoBoundary`
exercises that entire path with real test keys, Spring Security actors and Mongo transactions. It also confirms a
higher quality-gated state stays blocked without exact evidence. `VERIFIED` is not `CHAMPION`, and `CHAMPION` is not
runtime deployment authorization.
