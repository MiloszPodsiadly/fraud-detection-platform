# Alert Service Config Matrix

Status: current deployment configuration reference.

## Scope

This matrix summarizes deployment-profile posture for `alert-service` configuration. It is a configuration reference,
not a production approval, bank certification, external platform attestation, or proof that an environment is safe to
promote. Release readiness and external platform controls are documented under [release documentation](../release/index.md).

| Profile | Transaction mode | Trust refresh | External anchoring | Trust Authority signing | Auth | Sensitive read audit | Bank guard | Trust-level status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| local | OFF by default | PARTIAL allowed | optional | optional | demo or JWT by explicit opt-in | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| test | OFF/REQUIRED by test | PARTIAL allowed | optional | optional | test-controlled | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| docker-local | OFF/REQUIRED by compose | PARTIAL allowed | disabled by default | disabled by default | demo or local OIDC override | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| bank-local | OFF/REQUIRED by explicit smoke config | PARTIAL allowed | disabled allowed | disabled allowed | demo allowed | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| staging | REQUIRED | ATOMIC | enabled, required, fail-closed | required | JWT required, demo forbidden | fail closed | strict | BANK_PROFILE_ACTIVE when bank fail-closed enabled |
| prod | REQUIRED | ATOMIC | enabled, required, fail-closed | required | JWT required, demo forbidden | fail closed | strict | BANK_PROFILE_ACTIVE when bank fail-closed enabled |
| bank | REQUIRED | ATOMIC | enabled, required, fail-closed | required | JWT required, demo forbidden | fail closed | strict | BANK_PROFILE_ACTIVE |

`bank` means the strict bank-grade runtime closure gate. It requires FDP-24 external anchoring publication to be enabled, required, and fail-closed; the sink must be production-capable and not `disabled`, `noop`, `local-file`, `in-memory`, or `same-database`. `bank-local` is smoke-only and must not be described as regulator-grade. `prod`, `production`, `staging`, and `bank` profiles are institutional. Setting `app.audit.bank-mode.fail-closed=true` (environment variable `AUDIT_BANK_MODE_FAIL_CLOSED=true`) also activates institutional startup checks under any profile name.

Prod-like profiles must not use local/noop/in-memory/same-database external evidence sinks. FDP-27 is a production closure gate; it does not add KMS/HSM, legal notarization, WORM storage, broker-side verification, or a pre-commit/finalize redesign.

## Analyst API JWT trust

When JWT resource-server authentication is used in `prod`, `production`, `staging`, or `bank`, or when
`app.audit.bank-mode.fail-closed=true`, startup requires `app.security.jwt.required=true`, an explicit
`app.security.jwt.issuer-uri`, and `app.security.jwt.expected-audience`. Tokens must have a valid signature,
issuer, expiry/not-before times, and the configured audience in `aud` (a string or an array containing that value).
The same issuer and audience validators apply to issuer discovery and explicit JWKS configuration.

| Setting | Environment variable | Local/demo behavior | Protected deployment |
| --- | --- | --- | --- |
| Trusted issuer | `APP_SECURITY_JWT_ISSUER_URI` | Optional only for explicitly enabled local/test JWKS-only fixtures | Required; exact `iss` match |
| Expected API audience | `APP_SECURITY_JWT_EXPECTED_AUDIENCE` | Optional for lower-assurance local fixtures | Required; `aud` must contain the configured API audience |
| JWKS-only local opt-in | `APP_SECURITY_JWT_ALLOW_UNBOUND_JWKS_FOR_LOCAL` | Defaults to `false`; `true` works only with local/dev/docker-local/bank-local/test profiles | Forbidden as a substitute for issuer binding |

Configure the IdP to add `fraud-detection-platform-api` to the access-token `aud` claim before setting
`APP_SECURITY_JWT_EXPECTED_AUDIENCE` in a deployment. Roll out the audience mapper and issuer settings before
revoking old tokens or enabling protected traffic; tokens without `aud` will be rejected with 401. The local
Keycloak OIDC overlay provides this audience mapper for its evaluation clients. This is a token-validation
contract, not an IdP security certification.

## Model-promotion API rate limiting

| Runtime | `MODEL_PROMOTION_API_RATE_LIMITER_MODE` | Expected behavior |
| --- | --- | --- |
| Explicit single-node profile only: `local`, `dev`, `test`, `docker-local`, or `bank-local` | `in-memory` or `redis` | In-memory is process-local and is suitable only while one instance serves the API. Every active profile must be in this allowlist. |
| No active profile, `default`, plain `docker`, or any unlisted profile | `redis` | In-memory is rejected until an explicit single-node profile is selected. |
| `staging`, `prod`, `production`, `bank`, or any mixed profile set containing one of them | `redis` | Shared standalone Redis is mandatory; selecting `in-memory` fails application startup. |
| Any profile with `app.audit.bank-mode.fail-closed=true` | `redis` | Institutional mode applies regardless of profile spelling, including `default` and `bank-local`; `in-memory` fails startup. |
| Any runtime, unset/blank/unknown mode | invalid | Application startup fails; there is no implicit in-memory fallback. |

The local Compose `alert-service` selects the plain `docker` profile and defaults to Redis. That profile does not
authorize in-memory quotas; select an explicit allowlisted single-node profile before opting into in-memory.
Production-like profiles and `app.audit.bank-mode.fail-closed=true` always reject that mode.

The supported Redis topology is standalone/non-Cluster. Redis Cluster is deliberately unsupported because the current
Lua quota operation touches keys without a common hash tag; a `CROSSSLOT` or other Redis error is treated as limiter
unavailability and the governance request fails closed with `503`, while an exhausted quota returns `429`. See the
[rate-limiting operations runbook](../operations/model_promotion_api_rate_limiting.md) for configuration acceptance.

## Model-promotion issuer trust (separate from audit trust)

| Setting | Local default | Production deployment requirement |
| --- | --- | --- |
| `MODEL_PROMOTION_TRUSTED_ISSUERS_PATH` | Read-only `deployment/.local` public fixture via Compose | Required, readable reviewed public registry outside the image; missing path prevents institutional startup |
| `MODEL_PROMOTION_APPROVAL_TRUST_PATH` | Read-only `deployment/.local` public fixture via Compose | Required, readable reviewed public registry outside the image; missing path prevents institutional startup |
| `MODEL_PROMOTION_ONLINE_ACTOR_TRUST_PATH` | Read-only `deployment/.local` public fixture via Compose | Required, readable reviewed public registry outside the image; missing path prevents institutional startup |
| Registry `*_MIN_GENERATION` floors | `1` in the local overlay | Independently protected monotonic floors, raised with every approved registry change |
| `model_promotion_policy_trust_registry_generation` | Loaded generation gauge | Check per instance after restart and compare mounted file SHA-256 separately |

The alert service requires all three public registries in institutional mode: issuer keys,
approval keys, and online actor mandates. Missing, unreadable, malformed, or below-floor registries prevent startup;
an unset path remains `UNAVAILABLE` only in local mode. The alert service never receives issuer, approver, author,
or publisher private keys. The offline publisher separately receives its signing keys and its read-only public trust
inputs. Protect each registry's minimum-generation setting independently from the registry file. The local Compose
trust overlay mounts public generation-1 fixtures read-only and is not a production configuration. Full provisioning
and recovery steps are in [trust operations](../operations/model_promotion_trust_operations.md).

## WebAuthn, MongoDB, and audit persistence

| Contract | Local Compose/evaluation | Institutional deployment |
| --- | --- | --- |
| WebAuthn relying party | Defaults to `APP_MODEL_POLICY_WEBAUTHN_RP_ID=localhost` and `APP_MODEL_POLICY_WEBAUTHN_ORIGIN=http://localhost:4173` | Set both explicitly to a host-compatible RP ID and HTTPS origin. Localhost, `.localhost`, and loopback origins are rejected even when `app.audit.bank-mode.fail-closed=true` is selected under an unexpected profile. |
| Model-promotion persistence | `MODEL_PROMOTION_PERSISTENCE_ENABLED=true`; local Mongo runs as a one-member `rs0` replica set | Keep persistence startup verification enabled. `REQUIRED` Mongo transactions and the capability probe must pass; required collections and named indexes, including exact keys, uniqueness, and sparse semantics, are checked at startup. A standalone Mongo server cannot satisfy this transaction contract. |
| Mongo topology | `MONGODB_URI` in Compose selects `replicaSet=rs0`; this is a local transaction-capable fixture | Use a supported replica set or sharded cluster and a transaction-capable Mongo deployment. Configure the replica-set topology and credentials outside the application image. Startup index creation is bootstrap behavior; the guard verifies the resulting index contract. |
| Sensitive read audit | Bounded `read_access_audit_events` records are stored in local Mongo; local mode may be best effort | Set `SENSITIVE_READS_AUDIT_FAIL_CLOSED=true` for protected reads. This is application-level Mongo persistence, with no independent anchor or tamper-proof archive. See [sensitive read-audit assurance](../operations/sensitive_read_audit_assurance.md). |
| Governance audit evidence | Local Mongo outbox/chain and local Compose witness fixtures | External governance evidence depends on separately deployed witness and Trust Authority availability. It does not anchor sensitive read-audit records. No repository setting establishes a certification or an external receipt when the witness has not accepted one. |

The base [`docker-compose.yml`](../../deployment/docker-compose.yml) is local evaluation: its Redis service is standalone,
its Mongo service is a one-member replica set for transaction behavior, and it uses the `docker` profile. Keep all
replicas pointed to one shared **standalone Redis** in a protected deployment. Redis Cluster is unsupported: the quota
Lua script can return `CROSSSLOT`, which is treated as limiter failure and returns `503`, with no weaker fallback.

[`deployment/.env.example`](../../deployment/.env.example) contains local fixture values only. It does not provision
the protected registry mounts, generation floors, identity-provider settings, Mongo replica-set credentials, external
witness, or Trust Authority required by an institutional deployment.
