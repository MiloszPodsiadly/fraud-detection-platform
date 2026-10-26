# Security Architecture

Security is implemented as explicit runtime boundaries rather than documentation-only policy. `alert-service` owns analyst-facing authentication, endpoint authorization, RBAC, CSRF behavior, session bootstrap, and audit actor resolution. Internal calls into `ml-inference-service` use the service identity contract documented in [Internal service identity](internal_service_identity.md).

This document is the current security overview. Endpoint-level ownership lives in [Endpoint authorization map](endpoint_authorization_map.md).

## Runtime Boundaries

| Boundary | Runtime owner | Enforcement |
| --- | --- | --- |
| Browser to analyst APIs | `alert-service` | Spring Security, BFF session/OIDC when enabled, JWT resource server when enabled, local demo auth only in local profiles. |
| Analyst UI capability display | `analyst-console-ui` | UX gating only; backend authorization remains authoritative. |
| Fraud scoring and ML governance reads | Java services to `ml-inference-service` | Internal JWT service identity or mTLS service identity depending on deployment profile. |
| Audit writes | `alert-service` | Authenticated actor resolution and append-only audit records. |
| Operational health | Service runtime | Public health/info endpoints for local orchestration; production exposure must be controlled by deployment. |

## Authentication Modes

`alert-service` supports three analyst identity sources:

- BFF session/OIDC: browser session mode using provider-backed login, callback, session bootstrap, logout, and CSRF metadata.
- JWT resource server: stateless bearer API mode with configurable claim-to-authority mapping. Protected deployments
  require an exact trusted issuer and an expected resource audience; signatures, issuer, audience, `exp`, and `nbf`
  are validated before authority mapping. Explicit JWKS without issuer binding is an opt-in local/test fixture mode.
- Local demo auth: `local`, `dev` and `docker-local` only; automated `test` execution requires an explicit fixture marker. It is disabled by default and must not be treated as a production identity provider.

Demo auth is ignored when JWT auth is active. Controllers and services depend on `AnalystPrincipal` and `CurrentAnalystUser`, not on demo headers or provider-specific claim names.

## Authorization Model

Authorities are the backend enforcement contract. Roles describe analyst personas and are mapped into authorities before authorization checks.

| Authority | Capability |
| --- | --- |
| `alert:read` | List alerts and read alert detail. |
| `assistant-summary:read` | Read assistant summaries. |
| `alert:decision` | Submit analyst decisions. |
| `case:read` | Read fraud cases and work queues. |
| `case:assign` | Assign or claim fraud cases. |
| `case:decision` | Mutate fraud-case lifecycle decisions. |
| `transaction:read` | Read transaction-scoring views. |
| `audit:read` | Read audit, integrity, degradation, and evidence views. |
| `governance:read` | Read governance advisory analytics and projections. |
| `recovery:execute` | Run operational recovery actions. |
| `trust:read` | Read trust incidents and system trust level. |

Every protected backend route must be owned by one `*AuthorizationRules` group. Broad `permitAll` matchers are forbidden for backend-looking route families. Unknown backend-looking routes are denied before the SPA fallback.

## Endpoint Authorization

`AlertSecurityConfig` keeps one `SecurityFilterChain` to avoid filter ordering ambiguity. The chain delegates matcher ownership to `AlertEndpointAuthorizationRules`, which composes route groups in this order:

1. public technical routes
2. session/bootstrap routes
3. business and operational API route groups
4. BFF lifecycle routes
5. backend-looking deny-by-default routes
6. GET-only SPA fallback
7. final deny

`SecurityRouteOwnershipRegistry` mirrors expected MVC route ownership for CI guardrails. It does not configure Spring Security.

## Session And CSRF

`GET /api/v1/session` is a public bootstrap endpoint. It returns no-store session state and CSRF metadata only. It must not expose access tokens, refresh tokens, ID tokens, raw claims, provider groups, profile data, email addresses, session IDs, or provider logout URLs.

Unsafe cookie-backed routes require CSRF. Stateless bearer APIs may bypass CSRF only when no `JSESSIONID` is present, and they still require JWT authentication plus RBAC authorization.

## Audit Actor Identity

Write paths use the authenticated principal as the actor source of truth when available. Demo headers, JWT claims, and OIDC claims are translated before business services see identity. Audit records must not use browser-supplied actor fields as authoritative identity.

Security, auth, and session metrics are operational telemetry. They are not audit evidence and must not carry raw tokens, session IDs, user IDs, authorities, provider groups, raw claim values, URLs with query strings, certificate serial numbers, or exception messages in labels.

## Internal Service Identity

Internal service identity protects calls into `ml-inference-service`:

- `fraud-scoring-service` calls scoring with `ml-score`.
- `alert-service` calls governance advisory endpoints with `governance-read`.

Supported modes are documented in [Internal service identity](internal_service_identity.md). Production-like profiles must fail closed when credentials, key material, certificate material, authority mappings, or expected server identity are incomplete.

Local mTLS and JWT/JWKS material is generated on demand by `scripts/bootstrap-local-fixtures.sh` under
`deployment/.local/service-identity/`; private PEM keys are not committed. This preserves simple local evaluation
without treating fixture keys as production PKI, production secret management, production provenance, or an
independent external trust anchor.

## Model-promotion policy trust

The `model-promotion-policy-publisher` authenticates separately provisioned author, independent approver,
publisher and issuer credentials against deployment-controlled public trust registries. `alert-service`
loads the public actor, approval-key and issuer registries; it never holds an issuer private key or infers authority
from a Mongo policy document. Current policy `VERIFIED` requires an active issuer mandate and exact signature. Historical
`INTACT_*` inspection is not current authorization. Ordinary model-promotion reads omit original rationale content;
the approval-proof collection is not exposed by those APIs and requires restricted direct Mongo audit access.
The verifier reports only bounded status/reason metrics and a label-free loaded-registry generation gauge.
Protect each registry file and its independent minimum-generation setting against rollback. See
[trust operations](../operations/model_promotion_trust_operations.md); local fixture keys are not production keys.
The rollout view compares a declared Compose instance inventory with bounded runtime observations and the API's
configured file. These Mongo observations are operational telemetry, not independent deployment attestation;
`OBSERVED_CONSISTENT` does not establish `ACTIVATION_CONFIRMED`.

## Limitations

The repository provides a security implementation foundation, not a complete bank operating model. Production IAM governance, provider tenancy, secret rotation, certificate automation, ingress hardening, network policy, SIEM integration, WORM retention, and regulatory certification remain deployment and operating-environment responsibilities unless implemented explicitly in this repository.

Audit and regulated mutation controls are local service contracts. They are not distributed ACID. They do not provide exactly-once processing across Kafka, MongoDB, external audit storage, and downstream consumers.

## Threat model and executable boundaries

| Threat | Control and executable test | Residual limitation |
| --- | --- | --- |
| Cross-service JWT replay | JWT signature, exact issuer, audience, and time validation; the ML receiver also validates service identity. See [`JwtResourceServerRealDecoderTest`](../../alert-service/src/test/java/com/frauddetection/alert/security/config/JwtResourceServerRealDecoderTest.java) (`explicitJwksDecoderValidatesSignatureIssuerAudienceAndTimeClaims`) and [`test_server_metrics.py`](../../ml-inference-service/tests/test_server_metrics.py) (`test_token_validator_rejects_unknown_service`). | A stolen token remains usable by its intended audience until expiry; this service does not establish per-token revocation. Token issuance, storage, and rotation remain deployment controls. |
| Same subject from different issuers | Governance actor identity binds issuer and subject; reviewer identity must match the provisioned actor. See [`ModelPromotionSignedReviewServiceTest`](../../alert-service/src/test/java/com/frauddetection/alert/governance/modelpromotion/ModelPromotionSignedReviewServiceTest.java) (`reviewerWithSameSubjectFromAnotherIssuerCannotReview`). | Trust depends on exact configured issuer identifiers and their lifecycle at the identity provider. |
| Stale or revoked reviewer | Current actor mandate and credential status are checked when accepting a WebAuthn decision. See [`PolicyWebAuthnMongoIntegrationTest`](../../alert-service/src/test/java/com/frauddetection/alert/governance/modelpolicy/PolicyWebAuthnMongoIntegrationTest.java) (`revokedCredentialAndRevokedMandateBlockDecision`) and [`ModelPromotionPolicyPublisherMongoIntegrationTest`](../../model-promotion-policy-publisher/src/test/java/com/frauddetection/alert/governance/modelpromotion/ModelPromotionPolicyPublisherMongoIntegrationTest.java) (`currentTrustRevocationAndUnavailableApprovalRegistryBlockNewUseWithoutChangingHistory`). | Revocation blocks future use; it does not erase or retroactively invalidate an already committed historical decision. |
| Missing JWT `iss` or `aud` | Protected startup requires explicit issuer and expected audience; the real decoder rejects invalid claim sets. See [`AuthenticationStartupGuardTest`](../../alert-service/src/test/java/com/frauddetection/alert/security/config/AuthenticationStartupGuardTest.java) (`shouldRequireIssuerAndAudienceWhenBankFailClosedIsEnabledOnAnUnexpectedProfile`) and [`JwtResourceServerRealDecoderTest`](../../alert-service/src/test/java/com/frauddetection/alert/security/config/JwtResourceServerRealDecoderTest.java). | Correct audience and issuer claims still depend on identity-provider configuration; no external certification is implied. |
| Stolen or reused WebAuthn assertion | Challenge is single-use and bound to actor, operation, and exact contract; Mongo transaction commits evidence with the decision. See [`PolicyWebAuthnMongoIntegrationTest`](../../alert-service/src/test/java/com/frauddetection/alert/governance/modelpolicy/PolicyWebAuthnMongoIntegrationTest.java) (`sharedBoundAssertionRejectsChangedModelContractAndCrossDomainReplay`, `concurrentUseOfOneChallengeHasOneDurableDecision`). | A compromised browser/session or authenticator can race the legitimate user; one accepted use cannot distinguish who controlled the device. |
| Publisher loses its lease | Lease takeover and publication are fenced through durable Mongo state. See [`PolicyPublicationWorkerMongoIntegrationTest`](../../model-promotion-policy-publisher/src/test/java/com/frauddetection/alert/governance/modelpromotion/PolicyPublicationWorkerMongoIntegrationTest.java) (`slowSignerLosesLeaseToAnotherWorkerWithoutDuplicatePublication`). | The fence governs cooperating application workers; privileged direct database writes remain outside that boundary. |
| MongoDB commit acknowledgement is unknown | The store reconciles the exact durable decision and does not repeat the mutation. See [`MongoModelPromotionStoreIntegrationTest`](../../alert-service/src/test/java/com/frauddetection/alert/governance/modelpromotion/MongoModelPromotionStoreIntegrationTest.java) (`unknownCommitResultReadsDurableDecisionWithoutSecondMutation`). | While MongoDB remains unavailable, the caller may not learn whether the original commit completed. |
| Redis outage or Cluster `CROSSSLOT` | Shared standalone Redis is required in protected mode; limiter errors fail closed with `503`. See [`ModelPromotionApiRateLimiterTest`](../../alert-service/src/test/java/com/frauddetection/alert/governance/modelpromotion/api/ModelPromotionApiRateLimiterTest.java) (`redisAndClusterCrossSlotFailuresFailClosedWithoutActorLabels`). | Governance API availability depends on Redis; Redis Cluster is unsupported by the current Lua key layout. |
| Privileged MongoDB tampering with read-audit records | Fail-closed mode gates supported sensitive responses on a durable Mongo write. [`ReadAccessAuditServiceTest`](../../alert-service/src/test/java/com/frauddetection/alert/audit/read/ReadAccessAuditServiceTest.java) checks bounded records and persistence behavior. | There is no independent read-audit anchor or tamper detector; a privileged database operator can change or delete records. |
| Browser switches accounts | OIDC callback identity changes clear a selected detail before another account loads it. See [`App.test.jsx`](../../analyst-console-ui/src/App.test.jsx) (`clears a selected detail when the OIDC callback changes the active account`) and [`authProvider.test.js`](../../analyst-console-ui/src/auth/authProvider.test.js) (`prepares oidc provider around a token/session source boundary`). | These are application tests, not a live identity-provider/browser test; backend authorization remains authoritative. |

## Review Checklist

- New backend endpoints are present in [Endpoint authorization map](endpoint_authorization_map.md).
- Route ownership is registered in `SecurityRouteOwnershipRegistry`.
- Positive, anonymous, wrong-authority, unknown sibling, and unsafe-method/CSRF tests cover the route family.
- UI auth-sensitive calls go through `createAlertsApiClient({ session, authProvider })`.
- New metrics use bounded low-cardinality labels.
- New service-to-service calls use `InternalServiceAuthHeaders` and the shared internal HTTP client boundary.

### Sensitive read audit response boundary

Sensitive MVC responses use buffered response bodies (`DTO`, maps, lists and `ResponseEntity` bodies). The audit is
persisted before Spring serializes those bodies. Successful `200`, bodyless `204`, and conditional `304` responses
are covered; controller failures and authorization denials are audited while preserving their selected status.
In bank, staging, production, or explicitly fail-closed mode, an unavailable audit store produces a bounded `503`
before sensitive content is written. Missing actor identity also fails closed; ordinary `401`/`403` responses remain
intact. Streaming, asynchronous, `Resource`, and raw `InputStream` response types are unsupported for sensitive reads
and are rejected in fail-closed mode. Local fail-open mode remains best effort and emits bounded audit-failure metrics.
