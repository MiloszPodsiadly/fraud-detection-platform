# Model Promotion: internal trust operations

This procedure administers the two **public** Ed25519 trust registries. It does not make approval automatic. An
internal author, a different approver, and the publisher each need an explicitly provisioned mandate and a separately
controlled private credential. The issuer signs the policy with a fourth, separate key. No external approval provider
is required. Run the offline publisher from a protected operator host; never put private keys in MongoDB, the
`alert-service` image, its mounts, Git, or the browser. The `deployment/.local` fixture is only for development.

## Provision and validate

`scripts/model-promotion-trust-admin.mjs` is a cross-platform Node.js operator helper. All registry mutations write a
**new** file (`CREATE_NEW`); no command replaces an old generation. Use a protected directory outside the repository
for real credentials and registry change records. `keygen --private <PKCS8-base64-file> --public <SPKI-base64-file>`
creates one Ed25519 pair; run it separately for author A, approver B, publisher and issuer. To import an existing
Ed25519 public key, pass its canonical SPKI DER or base64-encoded DER file to `fingerprint` and `add-key`; keep its
private key under independent custody. Verify the printed SHA-256 against a second channel before granting a role.

```text
node scripts/model-promotion-trust-admin.mjs keygen --private <secure/author-a.private> --public <secure/author-a.spki>
node scripts/model-promotion-trust-admin.mjs fingerprint --public <secure/author-a.spki> --expected <independently-recorded-sha256>
node scripts/model-promotion-trust-admin.mjs init-registry --kind approval --out <secure/approval-0.json>
node scripts/model-promotion-trust-admin.mjs init-registry --kind issuer --out <secure/issuer-0.json>
node scripts/model-promotion-trust-admin.mjs add-key --kind approval --registry <secure/approval-0.json> --expected-generation 0 --out <secure/approval-1.json> --public <secure/author-a.spki> --identity <author-A> --key-id <author-key-1> --role POLICY_AUTHOR --environments PRODUCTION --valid-from <UTC> --valid-until <UTC>
```

Repeat `add-key` with a fresh output and the next expected generation for `POLICY_APPROVER` (B),
`POLICY_PUBLISHER`, and `POLICY_ISSUER`. Add that same issuer public key to the **separate** issuer registry with
`--kind issuer`, omitting `--role`. Both entries must have the same issuer identity, key ID, fingerprint, scope,
environment, intended use and validity. The helper fixes scope to `CURRENT_INSTITUTION` and intended use to
`FRAUD_RISK_SCORING`; it never grants a wildcard. The generation-zero files are staging only and cannot be deployed.
Key IDs and public-key fingerprints must be unique within each registry; a revoked physical key cannot be re-added
under an alias while its revoked entry is retained. Preserve retired/revoked entries through externally reviewed
registry change control: a higher generation alone cannot detect deliberate deletion of old entries. The same issuer
key intentionally appears once in **each** of the two distinct registries.
The first complete approval registry in this four-role example is generation 4, and the issuer registry generation 1.

Build the offline CLI (`mvn -pl model-promotion-policy-publisher -am -DskipTests package`) and run:

```text
MODEL_PROMOTION_APPROVAL_TRUST_MIN_GENERATION=4 MODEL_PROMOTION_TRUST_MIN_GENERATION=1 java -jar model-promotion-policy-publisher/target/model-promotion-policy-publisher-0.0.1-SNAPSHOT-exec.jar trust-check --approval-trust <secure/approval-4.json> --trusted-issuers <secure/issuer-1.json>
```

On PowerShell set those two environment variables with `$env:... = '4'` and `$env:... = '1'` before the same
`java -jar` command. `trust-check` uses the production registry parsers, enforces both protected generation floors,
and prints only public generation/fingerprint data. It must pass before deployment. The publisher uses **both**
registries; `alert-service` mounts only the public issuer registry. Provision the issuer path through
`MODEL_PROMOTION_TRUSTED_ISSUERS_PATH` and protect `MODEL_PROMOTION_TRUST_MIN_GENERATION` independently of that file.
Protect the publisher's `MODEL_PROMOTION_APPROVAL_TRUST_MIN_GENERATION` independently of its approval registry.
Neither floor may be reset by a routine rollout. The local Compose override deliberately fixes generation 1 and
development/test-only keys; do not use it as a production trust deployment.

The alert service loads the issuer registry **only at startup**. Restart each instance after a reviewed change,
then check its mounted file fingerprint and the label-free
`model_promotion_policy_trust_registry_generation` gauge on that instance. Confirm every instance reports the
approved generation before resuming governance decisions. A matching generation alone does not prove identical file
bytes: compare the CLI's `issuerRegistryFileSha256` to the mounted file through a protected deployment channel.
If the registry is absent or malformed, startup fails; keep that instance out of service and restore a reviewed
registry at or above the protected floor. Do not lower the floor to start an older file. The application cannot
prevent rollback if an attacker controls **both** the registry and its protected floor.

## Rotation, retirement, revocation

Generate/import a **new** key ID and add its public key as `ACTIVE` in a higher-generation registry before using
its private key. For issuer rotation, add the new key to both registries, independently review their fingerprints,
raise both floors, deploy, and confirm the new generation on every alert-service instance. Then publish a newly
approved policy using the new issuer key; `trust-check`, publisher dry-run and the real resolver must verify it.
Do not re-sign or silently rewrite existing policy or model-decision documents.
For an author, approver or publisher key rotation, use the same add/deploy/switch/retire sequence in the approval
registry only. A changed human identity requires a new mandate and an independently reviewed policy; changing the
registry does not transform an old signed approval into one by a new actor.

Stop use of the old private key. To retire it, choose a canonical UTC `--valid-until` at or after its last authorized
issuance and no later than now, then create a new registry file:

```text
node scripts/model-promotion-trust-admin.mjs set-status --kind issuer --registry <secure/issuer-N.json> --expected-generation N --out <secure/issuer-N+1.json> --key-id <old-key-id> --status RETIRED --valid-until <last-authorized-issuance-UTC>
```

Retire the corresponding `POLICY_ISSUER` approval mandate too, raise both protected floors and redeploy. A retired
key cannot authorize any new decision. If compromised, stop its signer immediately and use `set-status ... --status
REVOKED` (no `--valid-until`) in **both** registries, raise floors and restart all instances. The helper permits
`ACTIVE → RETIRED/REVOKED` and `RETIRED → REVOKED`, never restoration to `ACTIVE`; a later legitimate key requires a
new key ID. Preserve revoked public material and immutable policy/decision records for investigation. Reapprove and
reissue any still-needed policy under uncompromised credentials. Revocation cannot undo decisions already committed.

For historical inspection, export only the signed `policy` object from a restricted Mongo audit read into a protected
JSON file, then run `java -jar <exec-jar> inspect-policy --signed-policy <signed-policy.json> --trusted-issuers
<current-issuer-registry.json>` with the protected issuer generation floor. `INTACT_ACTIVE_KEY`,
`INTACT_RETIRED_KEY`, or `INTACT_REVOKED_KEY` describes cryptographic history only; compare the separate
`currentAuthorization` field. **INTACT is never VERIFIED** and never reauthorizes a new model decision. Approval
proofs in `model_promotion_policy_approval_evidence` require restricted Mongo audit access; ordinary read-only UI
users receive neither those proofs nor rationale content. Mongo local audit is not certified WORM storage.

For the end-to-end author → independent approval → signed publication workflow, use
[the publication procedure](model_promotion_policy_publication.md). The runnable test command
`mvn -pl model-promotion-policy-publisher -am -Dtest=ModelPromotionPolicyPublisherMongoIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
proves real Ed25519/Mongo trust followed by independent model review. A verified policy does **not** make a model
`CHAMPION`, and `CHAMPION` never authorizes runtime deployment. Full production eligibility still needs real,
exact-artifact evaluation evidence; missing measurements remain fail-closed.
