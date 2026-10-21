# Model-policy credential trust and recovery

Policy approval passkeys are bound to the OIDC issuer and subject and verified by the server with Yubico WebAuthn (exact origin and RP ID, fresh one-use challenge, user verification, ownership and signature counter). An OIDC login by itself is never enrollment authority. Client-provided `amr`, `acr`, or an MFA checkbox is not accepted as step-up evidence.

The local Keycloak realm does not currently provide a server-verifiable recent high-assurance authentication timestamp. Enrollment therefore uses an independently approved, short-lived grant. Two *different* OIDC identities in the dedicated `model-policy-credential-admin` group participate: one requests with target issuer, subject, purpose and case reference; the other approves. Neither may be the target. The returned 256-bit bearer token is shown once; Mongo retains only its SHA-256 digest. It expires after 10 minutes and is consumed with registration in one Mongo transaction. Deliver it to the target operator through a controlled channel; do not put it in source control or browser logs. The local fixture contains `policy-security-a` and `policy-security-b` only for development.

Keycloak imports the fixture when the realm is created; an already-persisted local realm is not silently rewritten. Add the two security-operator users and credential-holder group to an existing local realm through Keycloak administration, or recreate only that local development realm deliberately after preserving needed data.

| Situation | Required proof | Result |
| --- | --- | --- |
| First passkey | `FIRST` grant plus genuine registration ceremony | Active credential with approval provenance |
| Additional passkey | `ADDITIONAL` grant plus genuine registration ceremony | Existing passkey stays active; independent protection is still required |
| Normal revocation | Active passkey assertion bound to target credential and operation | Target permanently revoked |
| Lost device | `LOST_DEVICE_REVOCATION` grant for each inaccessible credential, then `LOST_DEVICE_RECOVERY` grant | Keys become `LOST` and cannot sign; new passkey gets a new identity |
| Suspected compromise | `COMPROMISE_REVOCATION` grant for affected credential, then `COMPROMISE_RECOVERY` grant when no active key remains | Compromised key cannot approve or recover access |

The grant request API is `POST /api/v1/governance/model-policies/webauthn/enrollment-grants`; approval is `POST /{id}/approvals`. The target sends `grantToken` to both `POST /webauthn/registrations` and `POST /webauthn/registrations/complete`. Normal revocation uses `POST /webauthn/credentials/{credentialId}/revocation-challenges` followed by `POST /webauthn/credentials/{credentialId}/revoke` with the signed assertion. Protected revocation uses `POST /webauthn/credentials/{credentialId}/protected-revoke` with `grantToken` and `compromised`.

All these paths require a real OIDC session and an explicit credential mandate; grant administration has a separate mandate. Consumed challenges/grants, credential state and append-only security events commit atomically. A duplicate operation or audit insert conflict rolls back the state change. A revoked key is never reactivated, including after service restart. This design protects against theft of a single target session; compromise of both independent security-operator accounts or interception of the delivered one-time grant remains an operational risk and requires incident handling.

Historical assertion validity and authorization for a **new** decision are separate. The server re-verifies the retained challenge, signed assertion and public key; it also checks the current OIDC actor, publisher and issuer mandates and their configured generation floors. Current signing always requires an active credential. `REVOKED` means routine retirement, `LOST` means inaccessible device, and `COMPROMISED` covers suspected or confirmed compromise. Use the compromise path when the cause is uncertain. Replacing a compromised credential does not restore reliance on an old policy: author and approver must independently review a new policy revision and publish it with current credentials and mandates; the old signed artifact is not rewritten.

| Credential / trust condition | Historical signature integrity | Can credential sign now? | Old policy can authorize a new decision? |
| --- | --- | --- | --- |
| Active; current actor, publisher and issuer mandates | Intact if retained proof verifies | Yes | Yes, if all other policy checks pass |
| Routine retirement (`REVOKED`) | Intact if retained proof verifies | No | Yes, if all current mandates remain valid |
| Lost device (`LOST`) | Intact if retained proof verifies | No | Yes, unless incident response determines compromise |
| Suspected or confirmed compromise (`COMPROMISED`) | Can remain historically intact | No | No; independently approve and issue a new revision |
| Expired/revoked actor mandate or retired/revoked issuer | Can remain historically intact | Depends on credential and actor mandate | No under the current mandate/issuer policy |
| Missing/altered challenge, assertion or public key | Invalid or unavailable | Irrelevant | No |
| Older trust-registry generation than configured floor or retained approval generation | Not a retroactive statement about signature validity | Depends on credential | No; restore current trusted configuration |

Unused expired challenges are removed only after seven days by the scheduled cleanup. Used challenges, assertions, credential public keys and immutable publication proofs have no blanket TTL; retain them for the policy/audit retention period and archive them together before any controlled purge. Removing a referenced challenge or public key makes verification fail closed. A lost HTTP response or process restart does not require a new key for already committed evidence.
