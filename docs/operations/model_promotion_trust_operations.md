# Model Promotion: internal trust operations

This procedure administers the public Ed25519 approval and issuer registries and the separate online actor mandate registry. It does not make approval automatic. An
internal author, a different approver, and the publisher each need an explicitly provisioned mandate and a separately
controlled private credential. The issuer signs the policy with a fourth, separate key. No external approval provider
is required. Run the offline publisher from a protected operator host; never put private keys in MongoDB, the
`alert-service` image, its mounts, Git, or the browser. The `deployment/.local` fixture is only for development.

## Root bootstrap and reviewed changes

The first trusted generation and its protected minimum-generation setting are installed by an out-of-band institutional operator. The web workflow cannot authorize its own initial root of trust. Keep this bootstrap record, the registry file and the floor under separate protected change control. Later web requests load the complete current file, validate its schema and floor, apply exactly one structured action, and compute the canonical JSON SHA-256 on the server. The maker and an independent reviewer sign the action and exact candidate digest with separate WebAuthn credentials. The candidate, semantic diff and request are retained in Mongo; approval leaves the request at `PENDING_OPERATOR_ACTION` and does not write a live trust file.

For a later change, the reviewer opens the stored candidate diff in Settings / Security before signing. It shows the old and proposed entry, roles, environments, scope, key fingerprint, validity, base/candidate generations and exact digest. A client-supplied candidate digest is accepted only if it equals the server reconstruction; the browser no longer asks for one. `GRANT` adds a new exact OIDC actor, `ADD` adds a new unique key, `ROTATE` replaces an active key without broadening its roles or scope, and `RETIRE` / `REVOKE` preserve all unrelated entries. A key retirement cutoff must already have occurred, and a rotated key must currently be effective.

The immutable `model_policy_trust_candidates` record contains the operation ID, registry and action, base generation/digest, candidate generation, complete canonical registry JSON, candidate SHA-256, machine-readable semantic diff, review summary and whether the action affects production. Canonicalization sorts JSON object keys recursively, preserves array order, emits UTF-8 with one trailing newline, then hashes those exact bytes with SHA-256. The production registry parser validates both the source and complete candidate. The request WebAuthn contract binds the action intent, base and candidate digest; the independent review contract binds the request ID, same digests and APPROVE/REJECT. State is `PENDING_REVIEW → PENDING_OPERATOR_ACTION` or `PENDING_REVIEW → REJECTED`. A unique Mongo generation claim prevents two approved candidates for the same registry generation. Deployment observations are separate from approval and never change the decision state.

Export `GET /api/v1/governance/policy-trust/requests/{id}/installation` through a protected, authenticated audit channel to a file on the operator host. The API releases only an approved request after verifying both retained WebAuthn signatures. The export contains public candidate material and restricted assertion evidence, so protect it as an audit artifact. On the host, run the publisher CLI with the current protected source file and a **new** output path:

```text
java -jar <publisher-exec.jar> stage-approved-trust --approved-bundle <protected-export.json> --current-registry <loaded-registry.json> --out <new-generation.json>
```

The installer independently replays the signatures, reconstructs the candidate from the current validated source, checks the whole canonical content, digest, generation and protected floor, then creates a new file without overwriting the old one. Deliver only that staged file through the protected deployment channel, raise the independently controlled floor, restart or reload every affected instance, and run `verify-loaded-trust --registry-kind <ONLINE_ACTORS|APPROVAL_KEYS|ISSUER_KEYS> --current-registry <mounted-file.json>` on the loaded file. Compare its reported generation and canonical SHA-256 with the approved request and each instance observation. A missing deployment remains pending; a different file or stale base is refused. Do not lower the floor to recover a failed rollout.

## Provision and validate

`scripts/model-promotion-trust-admin.mjs` is a cross-platform Node.js operator helper. All registry mutations write a
**new** file (`CREATE_NEW`); no command replaces an old generation. Use a protected directory outside the repository
for real credentials and registry change records. `keygen --private <PKCS8-base64-file> --public <SPKI-base64-file>`
creates one Ed25519 pair; run it separately for author A, approver B, publisher and issuer. To import an existing
Ed25519 public key, pass its canonical SPKI DER or base64-encoded DER file to `fingerprint` and `add-key`; keep its
private key under independent custody. Verify the printed SHA-256 against a second channel before granting a role.
Files created by `add-key` or `set-status` are unapproved drafts for protected bootstrap or planning; they are not
installation authorization for a later web-governed change. Deploy a later generation only from the approved bundle
checked by `stage-approved-trust`.

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
and prints only public generation/fingerprint data. It must pass before deployment. The online publisher and
`alert-service` load the issuer, approval-key and online-actor public registries. Provision the issuer path through
`MODEL_PROMOTION_TRUSTED_ISSUERS_PATH` and protect `MODEL_PROMOTION_TRUST_MIN_GENERATION` independently of that file.
Protect the publisher's `MODEL_PROMOTION_APPROVAL_TRUST_MIN_GENERATION` independently of its approval registry.
Neither floor may be reset by a routine rollout. The local Compose override deliberately fixes generation 1 and
development/test-only keys; do not use it as a production trust deployment.

The alert service loads its verifier registries **only at startup**. Restart each instance after a reviewed change,
then check its mounted file fingerprint and the label-free
`model_promotion_policy_trust_registry_generation` gauge on that instance. Confirm every instance reports the
approved generation before resuming governance decisions. A matching generation alone does not prove identical file
bytes: compare the CLI's `issuerRegistryFileSha256` to the mounted file through a protected deployment channel.
If the registry is absent or malformed, startup fails; keep that instance out of service and restore a reviewed
registry at or above the protected floor. Do not lower the floor to start an older file. The application cannot
prevent rollback if an attacker controls **both** the registry and its protected floor.

## Rollout membership and observation

The supported local Compose topology declares one `ALERT_VERIFIER` (`alert-service`) and one `PUBLISHER`
(`model-promotion-policy-publisher`). Both receive `MODEL_PROMOTION_TRUST_DEPLOYMENT_ID`, their stable
`MODEL_PROMOTION_TRUST_INSTANCE_ID`, and the same comma-separated
`MODEL_PROMOTION_TRUST_EXPECTED_ALERT_INSTANCES` / `MODEL_PROMOTION_TRUST_EXPECTED_PUBLISHER_INSTANCES`.
Other deployments must supply their complete active instance list through protected deployment configuration;
without it the view is `UNKNOWN` and the online publisher does not start a new signing attempt. These settings
are a declared inventory, not independent discovery of a cluster. The deployment ID identifies a rollout and is
separate from the signed candidate request ID.

Each process reports the registries held by its live verifier or publisher objects, with its stable instance ID,
new process epoch, process start and heartbeat time. A changed file alone cannot update that report. The view
requires every declared instance to report the exact approved generation and canonical digest for the same
deployment within 90 seconds, and the API's configured registry file must be ready with that exact generation
and digest. A missing or stale instance, two fresh process epochs for one instance, an extra
reporter, wrong digest, or generation below the protected floor prevents observed consistency. A restart may show
two fresh epochs until the old heartbeat expires. Reports are Mongo telemetry without a cryptographic deployment
attestation; `OBSERVED_CONSISTENT` never means `ACTIVATION_CONFIRMED`. The publisher also checks all three loaded
registries across declared members before new signing and again before persistence. It pauses new claims while
membership is unknown or mismatched; an in-flight mismatch is recorded as `FLEET_TRUST_INCONSISTENT`.

| Condition | Rollout state | Operator action |
| --- | --- | --- |
| Awaiting independent review | `PENDING_INDEPENDENT_REVIEW` | Complete separate signed review. |
| Approved with no fresh members | `APPROVED_PENDING_OPERATOR_ACTION` | Install the exact candidate and restart instances. |
| Some members on old/missing/stale trust | `DEPLOYMENT_IN_PROGRESS` or `OBSERVED_PARTIAL` | Complete rollout and inspect each missing or stale member. |
| Duplicate/unexpected member or same-generation wrong digest | `DEPLOYMENT_FAILED` | Stop rollout and reconcile inventory and mounted files. |
| Loaded members match but the API registry file is unavailable or differs | `DEPLOYMENT_FAILED` | Install the exact file on the API instance and recheck the loaded trust. |
| Generation below protected floor | `REVOKED_OR_ROLLED_BACK` | Restore approved trust at or above the floor. |
| Every declared member reports exact loaded trust | `OBSERVED_CONSISTENT` | Independently confirm installation and health; activation remains unconfirmed here. |
| Membership or observation set unavailable | `UNKNOWN` | Establish a protected complete inventory and fresh observations. |

The application has no Kubernetes or external fleet inventory, and it cannot prove that a declared list includes
every process able to use the database or signing material. Protect Mongo writes and deployment configuration
separately. Emergency revocation needs a higher-generation exact registry on every affected process; a partial
rollout blocks new online publication, while historical signature checks continue to distinguish routine
retirement from compromise.
`CHANGE_REQUESTED` is the request API operation before its durable `PENDING_REVIEW` record exists;
`ACTIVATION_CONFIRMED` is unsupported by this deployment model and is never emitted.

## Rotation, retirement, revocation

Generate/import a **new** key ID and authorize its public key in a higher-generation registry before using
its private key. A reviewed `ROTATE` candidate retires the old key at the specified cutoff and adds the new key in
one exact registry generation; a reviewed `ADD` followed by a separate `RETIRE` is also supported. For issuer
rotation, change both the approval and issuer registries through separate reviewed candidates, independently verify
their fingerprints, stage and deploy each approved file, raise both floors, and confirm the new generations on every
affected instance. Then publish a newly
approved policy using the new issuer key; `trust-check`, publisher dry-run and the real resolver must verify it.
Do not re-sign or silently rewrite existing policy or model-decision documents.
For an author, approver or publisher key rotation, use the same add/deploy/switch/retire sequence in the approval
registry only. A changed human identity requires a new mandate and an independently reviewed policy; changing the
registry does not transform an old signed approval into one by a new actor.

Stop use of the old private key. For an approved web-governed retirement, choose a canonical UTC cutoff at or after
its last authorized issuance and no later than now. The offline helper can produce a comparison draft:

```text
node scripts/model-promotion-trust-admin.mjs set-status --kind issuer --registry <secure/issuer-N.json> --expected-generation N --out <secure/issuer-N+1.json> --key-id <old-key-id> --status RETIRED --valid-until <last-authorized-issuance-UTC>
```

Submit each exact candidate through the maker/checker workflow and install only the approved manifest. Retire the
corresponding `POLICY_ISSUER` approval mandate too, raise both protected floors and redeploy. A retired
key cannot authorize any new decision. If compromised, stop its signer immediately, submit and independently approve
`REVOKE` for **both** registries, then stage the exact approved files, raise floors and restart all affected instances.
The helper can create a comparison draft with `set-status ... --status REVOKED` (no `--valid-until`). It permits
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
