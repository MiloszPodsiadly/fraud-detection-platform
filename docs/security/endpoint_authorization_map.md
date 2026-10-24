# Endpoint Authorization Map

Status: current source of truth for backend route ownership and authorization matcher intent.

The security route boundary is architecture hardening only. It decomposes authorization rules and configurers while preserving one `SecurityFilterChain` to avoid filter-order ambiguity.

No new business endpoints, fraud-case lifecycle changes, idempotency changes, `RegulatedMutationCoordinator` changes, Kafka/outbox/finality changes, export workflow, bulk action workflow, assignment workflow, or UI feature work are part of this boundary.

## Non-Goals

The security route boundary does not add business endpoints, change fraud-case lifecycle semantics, change idempotency behavior, change `RegulatedMutationCoordinator`, change Kafka/outbox/finality behavior, change export workflows, change bulk action workflows, change assignment workflows, or add UI product features.

The security route boundary does not certify enterprise IAM readiness. Production IAM governance, provider configuration, secret rotation, and deployment hardening remain deployment responsibilities unless configured in this repo.

## /api/v1/session and CSRF metadata

`GET /api/v1/session` is a public BFF bootstrap endpoint owned by `SessionAuthorizationRules`. It returns no-store session state and CSRF metadata only. The CSRF token is not authentication material, is not a secret against XSS, and must not be treated as a bearer credential.

The session response must not expose an access token, refresh token, ID token, JWT, raw claims, provider groups, profile data, email address, or session ID. Frontend roles/authorities are display/capability hints only; backend Spring Security remains the enforcement source.

Unsafe cookie-backed routes require CSRF. Stateless bearer APIs may bypass CSRF only when no `JSESSIONID` is present, and they still require valid JWT authentication and RBAC authorization.

## Adding a new backend endpoint safely

Every backend endpoint must be explicitly owned by a route group. New endpoints under `/api/**`, `/api/v1/**`, `/internal/**`, `/governance/**`, `/system/**`, `/bff/**`, and `/actuator/**` are denied by default until a route group allowlists them. This is the security route boundary deny by default rule.

Never add broad `permitAll` rules such as `/api/**`, `/api/v1/**`, `/internal/**`, `/governance/**`, `/system/**`, or `/bff/**`. Every public endpoint must explain why it is public.

The SPA fallback must remain GET-only and must not catch backend-looking paths. Unsafe cookie-backed routes require CSRF. Stateless bearer APIs may bypass CSRF only when no `JSESSIONID` is present, and they still require valid JWT authentication and RBAC authorization.

Every endpoint addition must include a correct-authority test, wrong-authority test, anonymous test, unknown sibling route test, and unsafe method/CSRF test when applicable.

SecurityRouteOwnershipRegistry is a CI guardrail that mirrors expected MVC ownership. It does not configure Spring Security.

## Maintainer checklist for new endpoints

- Add or update the owning `*AuthorizationRules` class.
- Register the route group in `AlertEndpointAuthorizationRules` before deny-by-default and before the SPA fallback only when the route is intentionally reachable.
- Add the route owner to `SecurityRouteOwnershipRegistry`.
- Update this route ownership map with the route family, owner, authority, classification, CSRF behavior, and expected test.
- Add controller mapping coverage through `RouteCoverageAgainstMvcMappingsTest`.
- Add route group coverage through `AuthorizationRulesCoverageTest`.
- Add wrong-authority, anonymous, unknown sibling, and unsafe-method/CSRF tests.
- Keep the SPA fallback GET-only and out of backend-looking route families.
- Do not add broad `permitAll` matchers for `/api/**`, `/api/v1/**`, `/governance/**`, `/system/**`, `/bff/**`, or `/actuator/**`.
- Keep frontend auth-sensitive calls behind `createAlertsApiClient({ session, authProvider })`; raw fetch belongs only in API/auth bootstrap layers.

## Choosing the route owner for cross-domain endpoints

Do not choose an owner based on URL prefix alone. Choose owner by resource semantics and required authority. Every ambiguous endpoint must include a one-line ownership rationale in this document.

- `FraudCaseAuthorizationRules` owns the current fraud-case work queue, detail, evidence read and regulated update routes only when the primary resource is a fraud case.
- `AuditAuthorizationRules` owns audit evidence, audit integrity, audit export, audit degradations, and trust-attestation evidence routes.
- `SuspiciousTransactionAuthorizationRules` owns protected internal SuspiciousTransaction read-model routes.
- `GovernanceAuthorizationRules` owns governance advisory projection, analytics, advisory audit routes, and explicitly authorized governance diagnostic read APIs.
- `RecoveryAuthorizationRules` owns operational recovery routes: outbox recovery, regulated mutation recovery, and unknown confirmations.
- `TrustAuthorizationRules` owns trust incident and system trust-level operational views.
- `BffAuthorizationRules` owns BFF lifecycle routes only, not business API routes.
- `SessionAuthorizationRules` owns session/bootstrap/OAuth routes only.
- If ownership is ambiguous, prefer the most restrictive operational owner and add tests for wrong-authority rejection.

## Auth observability boundaries

Auth/session/security metrics are operational signals, not audit evidence. They must not place raw tokens, session IDs, user IDs, authorities, role values, provider groups, raw OIDC claim values, provider logout URLs, or query strings in metric labels.

BFF session, logout, CSRF, and OIDC mapping events use low-cardinality outcome labels. Sensitive read audit remains separate from operational metrics.

## Metrics ownership follow-up

Future security/auth metrics should be owned by `AuthSecurityMetrics`. Fraud-case metrics should remain under `FraudCaseMetrics`, and transaction-scoring metrics should remain under `TransactionMetrics`. New metrics must avoid high-cardinality labels such as user IDs, session IDs, tokens, authorities, raw claims, provider groups, provider logout URLs, or query strings.

## Production BFF Deployment Checklist

Production BFF deployment requires:

- HTTPS-only ingress.
- Secure session cookie.
- SameSite policy selected and documented.
- Reverse proxy forwarded headers configured.
- Explicit allowed provider logout origins.
- Explicit allowed post logout redirect origins.
- Issuer URI per environment.
- Client ID and client secret from secret manager or environment, not code.
- Session timeout policy.
- IdP health and latency monitoring.
- CSRF unsafe-method tests in the deployment pipeline.
- direct SPA OIDC disabled unless separately approved.
- demo auth disabled.
- Local, dev, and test profile escape hatches disabled.

Production BFF hardening remains deployment responsibility unless configured in this repo. BFF mode is a safer browser auth foundation, not a replacement for production IAM governance. Backend Spring Security remains the enforcement source.

## Route Ownership

| Route family | Owner | Authority | Classification | CSRF behavior | Expected test |
| --- | --- | --- | --- | --- | --- |
| `/actuator/health/**`, `/actuator/info` | `PublicTechnicalAuthorizationRules` | Public technical endpoint | Public | Safe methods only | `SecurityMatcherOrderRegressionTest` |
| Static frontend assets and root document | `PublicTechnicalAuthorizationRules` | Public static content | Public | Safe methods only | `SecurityMatcherOrderRegressionTest` |
| `GET /api/v1/session` | `SessionAuthorizationRules` | Public bootstrap | Public, no-store, token-free | Safe method only | `BffSessionSecurityIntegrationTest` |
| `GET /oauth2/**`, `GET /login/oauth2/**`, `GET /error` | `SessionAuthorizationRules` | OAuth/session bootstrap | Public auth lifecycle | Framework controlled safe method only | `SecurityMatcherOrderRegressionTest` |
| `/api/v1/alerts/**` | `AlertAuthorizationRules` | `ALERT_READ`, `ASSISTANT_SUMMARY_READ`, `ALERT_DECISION_SUBMIT` | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `GET /api/v1/fraud-cases/work-queue`, `GET /api/v1/fraud-cases/work-queue/summary`, `GET /api/v1/fraud-cases/{caseId}`, `GET /api/v1/fraud-cases/{caseId}/evidence-summary`, `GET /api/v1/fraud-cases/{caseId}/evidence-timeline`, `PATCH /api/v1/fraud-cases/{caseId}` | `FraudCaseAuthorizationRules` | `FRAUD_CASE_READ`, `FRAUD_CASE_UPDATE` | Protected current FDP-81 surface | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/transactions/scored`, `GET /api/v1/transactions/scored/{transactionId}/engine-intelligence` | `TransactionAuthorizationRules` | `TRANSACTION_MONITOR_READ` | Protected transaction reads. Engine intelligence is a bounded optional diagnostic subresource. | Safe method only | `AuthorizationRulesCoverageTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/transactions/scored/{transactionId}/feedback` | `TransactionAuthorizationRules` | `FRAUD_FEEDBACK_READ` | Protected transaction-scoped bounded fraud feedback read. Returns the single recorded analyst review outcome for the transaction, if present. Feedback read is separate from `TRANSACTION_MONITOR_READ`; it is review/evaluation only and does not change scoring, recommendations, workflow, cases, payment authorization, model promotion, or thresholds. Raw notes are not returned. | Safe method only | `FraudFeedbackSecurityIntegrationTest`, `AuthorizationRulesCoverageTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `POST /api/v1/transactions/scored/{transactionId}/feedback` | `TransactionAuthorizationRules` | `FRAUD_FEEDBACK_WRITE` | Protected transaction-scoped bounded fraud feedback write. Records one active analyst review outcome per transaction, requires an authenticated analyst actor, and requires successful audit for a successful response. Decision/label pairs and reason codes are bounded, required, allowlisted, and label-compatible. Duplicate submissions return conflict; audit failure after persistence returns a controlled unavailable response after best-effort cleanup. Captured feedback is an evaluation signal and is not executed as a decision. Audit outbox/atomicity hardening is future shared write-action scope. | Unsafe cookie-backed requests require CSRF | `FraudFeedbackSecurityIntegrationTest`, `AuthorizationRulesCoverageTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback` | `TransactionAuthorizationRules` | `ENGINE_INTELLIGENCE_FEEDBACK_READ` | Protected transaction-scoped feedback read model. Returns the latest bounded first page with `hasMore`; cursor continuation is future scope. Feedback is governance/review only and cannot be read with transaction display or write authority alone. | Safe method only | `AuthorizationRulesCoverageTest`, `RouteCoverageAgainstMvcMappingsTest`, `AlertSecurityConfigTest` |
| `POST /api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback` | `TransactionAuthorizationRules` | `ENGINE_INTELLIGENCE_FEEDBACK_WRITE` | Protected write/review endpoint. Feedback requires write/review authority, not read-only permission. Captured feedback is audited and not executed. | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /internal/suspicious-transactions`, `GET /internal/suspicious-transactions/summary`, `GET /internal/suspicious-transactions/{suspiciousTransactionId}` | `SuspiciousTransactionAuthorizationRules` | `SUSPICIOUS_TRANSACTION_READ` | Protected internal read-only API | Safe method only | `AuthorizationRulesCoverageTest` |
| `GET /internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert` | `SuspiciousTransactionAuthorizationRules` | `SUSPICIOUS_TRANSACTION_READ` + `ALERT_READ` | Protected internal backend-resolved linked-alert read context. Client-selected `alertId` query parameter is rejected. | Safe method only | `AuthorizationRulesCoverageTest`, `SuspiciousTransactionReadControllerAuthorizationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `/governance/advisories/**` | `GovernanceAuthorizationRules` | `TRANSACTION_MONITOR_READ`, `GOVERNANCE_ADVISORY_AUDIT_WRITE` | Protected or denied | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `GET /api/v2/governance/shadow-performance/summary/current` | `GovernanceAuthorizationRules` | `SHADOW_PERFORMANCE_READ` | Protected read-only diagnostic response over validated Shadow Performance Summary V2 from the manifest-backed `current-summary.json` artifact set. It grants no promotion approval, threshold recommendation, payment authorization, or automatic decisioning authority. Not granted by generic transaction, fraud-case, or analyst read authorities. | Safe method only | `ShadowPerformanceSummaryControllerAuthorizationTest`, `RouteCoverageAgainstMvcMappingsTest`, `ShadowPerformanceReadApiArchitectureGuardTest` |
| `GET /api/v1/governance/promotion-review-readiness/current` | `GovernanceAuthorizationRules` | `PROMOTION_READINESS_READ` | Protected read-only diagnostic report over validated FDP-111 Promotion Review Readiness Report. Not granted by generic transaction, fraud-case, shadow-performance, or analyst read authorities. | Safe method only | `PromotionReviewReadinessReportControllerAuthorizationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/model-card/current` | `GovernanceAuthorizationRules` | `MODEL_CARD_READ` | Protected read-only response over one explicitly configured, manifest-verified exact-artifact Model Card. It grants no promotion, threshold, payment, workflow, or production decision authority. | Safe method only | `ModelCardControllerAuthorizationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/model-shadow-performance` | `GovernanceAuthorizationRules` | `MODEL_SHADOW_PERFORMANCE_READ` | Protected read-only response selected by exact model name, version, feature contract, artifact SHA-256, and evaluation window. No latest lookup or neighboring-artifact fallback is allowed; evidence remains diagnostic only. | Safe method only | `ExactModelShadowPerformanceControllerAuthorizationTest`, `ArtifactBackedExactModelShadowPerformanceProviderTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/model-promotion/state`, `GET /api/v1/governance/model-promotion/assessment` | `GovernanceAuthorizationRules` / `ModelPromotionApiController` | `MODEL_PROMOTION_READ` plus the requested environment scope authority, enforced by `ModelPromotionWorkflowService`; original rationale content in state additionally requires `MODEL_PROMOTION_RATIONALE_READ` and the same scope | Protected exact-artifact governance state/history and evidence assessment reads; `@AuditedSensitiveRead` applies. Ordinary readers receive rationale references and digests without original text. | Safe methods only | `ModelPromotionApiControllerTest`, `ModelPromotionHttpIntegrationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `POST /api/v1/governance/model-promotion/candidates`, `/proposals`, `/retirement-proposals`, `/proposals/{proposalId}/reviews`, `/proposals/{proposalId}/reviews/challenges`, `/proposals/{proposalId}/reviews/signed`; `GET /proposals/{proposalId}/reviews/operations/{operationId}` | `GovernanceAuthorizationRules` / `ModelPromotionApiController`, `ModelPromotionSignedReviewController` | Separate register/propose/retire/review mandates and environment scope; signed mutations require OIDC and an enrolled passkey; receipt read additionally checks the original reviewer | [Signed review boundary](model_promotion_signed_reviews.md): every normal production review, including retirement, requires WebAuthn. An uncertain response is resolved from the actor-scoped receipt. All accepted mutations remain transactional and audited. | Unsafe cookie-backed requests require CSRF | `ModelPromotionWorkflowServiceTest`, `ModelPromotionSignedReviewServiceTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `POST /api/v1/governance/model-promotion/proposals/{proposalId}/reviews/emergency-authorizations`, `/emergency`; `GET /api/v1/governance/model-promotion/emergency-authorizations/{id}`; `POST /emergency-authorizations/{id}/approve`, `/revoke`; `GET /emergency-authorizations/{id}/audit` | `GovernanceAuthorizationRules` / `ModelPromotionEmergencyRetirementController` | Dedicated emergency requester and independent authorizer OIDC groups; normal approve/retire and production scope for requester; dedicated audit plus production scope for audit | Exact five-minute, one-use grant for a pending production RETIRED proposal; no rationale-prefix fallback; [emergency authority contract](model_promotion_signed_reviews.md) | Unsafe cookie-backed requests require CSRF | `MongoModelPromotionStoreIntegrationTest`, `ModelPromotionWorkflowServiceTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/model-policies`, `/me`, `/{id}`, `/{id}/publication` | `GovernanceAuthorizationRules` / `PolicyGovernanceController`, `PolicyPublicationStatusController` | `model-policy:read`; production records additionally require explicit `model-policy:scope:production` in the application service | Scoped policy and current publication/trust reads. `READ` alone does not expose audit history. | Safe methods only | `RouteCoverageAgainstMvcMappingsTest`, `PolicyGovernanceSecurityTest` |
| `GET /api/v1/governance/model-policies/{id}/history` | `GovernanceAuthorizationRules` / `PolicyGovernanceController` | `model-policy:audit:read` plus scope check | Authorized history includes retained review rationale, not private credential material. | Safe method only | `RouteCoverageAgainstMvcMappingsTest`, `PolicyGovernanceSecurityTest` |
| `POST /api/v1/governance/model-policies/drafts`, `PUT /api/v1/governance/model-policies/drafts/{id}`, `POST /api/v1/governance/model-policies/{id}/publication-requests` | `GovernanceAuthorizationRules` / `PolicyGovernanceController` | `model-policy:create` for drafts; separate `model-policy:publish:request` for publication; production requires explicit scope grant | Author/publisher identity, maker-checker and version checks remain backend-owned. | Unsafe cookie-backed requests require CSRF | `RouteCoverageAgainstMvcMappingsTest`, `PolicyGovernanceSecurityTest` |
| `GET/POST /api/v1/governance/model-policies/{id}/publication/recovery` | `GovernanceAuthorizationRules` / `PolicyPublicationRecoveryController` | Dedicated `model-policy:publication:recover`; production also requires production scope and approval mandate | Operator must be independent of original author, approver and requester; only bounded operational retry is queued. | POST requires CSRF on cookie-backed sessions | `PolicyPublicationWorkerMongoIntegrationTest` |
| `GET /api/v1/governance/model-policies/schema`, `/page`, `/operations/{operationId}` | `GovernanceAuthorizationRules` / `PolicyGovernanceController` | `model-policy:read`; operation receipt additionally restricted to original actor and proposal scope | Server-owned criterion schema; bounded scoped cursor; no cross-actor receipt disclosure. | Read-only | `PolicyGovernancePaginationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/model-policies/{id}/history/page`, `GET /api/v1/governance/model-policies/approvers` | `GovernanceAuthorizationRules` / `PolicyGovernanceController`, `OnlinePolicyApproverDirectoryController` | `model-policy:audit:read` for history; separate `model-policy:directory:read` for eligible approvers | Bounded history; directory filters active mandate and scope and excludes caller. | Read-only | `OnlinePolicyApproverDirectoryTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `POST /api/v1/governance/model-policies/{id}/submit`, `/{id}/submit/challenges`, `/{id}/review`, `/{id}/review/challenges`; `POST /webauthn/registrations`, `/webauthn/registrations/complete`, `/webauthn/credentials/{credentialId}/*`; `GET /webauthn/credentials` | `GovernanceAuthorizationRules` / `PolicyGovernanceController` | Decision-specific mandate or `model-policy:credential:enroll`; all credential mutations require OIDC plus protected grant or WebAuthn assertion | [Credential lifecycle](model_policy_credential_lifecycle.md) | Unsafe cookie-backed requests require CSRF | `PolicyWebAuthnMongoIntegrationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `POST /api/v1/governance/model-policies/webauthn/enrollment-grants`, `GET /{id}/review`, `POST /{id}/review[/challenges]`, `POST /{id}/bootstrap-approval` | `GovernanceAuthorizationRules` / `PolicyCredentialEnrollmentGrantController` | Dedicated `model-policy:credential:admin`; independent signed WebAuthn review or exact protected first-install authority | Approval returns metadata only; bootstrap is single-use and limited to first enrollment | Unsafe cookie-backed requests require CSRF | `PolicyWebAuthnMongoIntegrationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/model-policies/webauthn/enrollment-grants/mine`, `POST /{id}/claim`, `GET /{id}/evidence` | `GovernanceAuthorizationRules` / `PolicyCredentialEnrollmentGrantController` | Target-only `model-policy:credential:enroll` for mine/claim; separate audit mandate for evidence | Target claims a one-time capability after evidence verification; no secret in listings or audit | Claim requires CSRF; evidence is audited | `PolicyWebAuthnMongoIntegrationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/policy-security/me`, `GET /api/v1/governance/policy-security/operations` | `GovernanceAuthorizationRules` / `PolicySecurityActorController`, `PolicySecurityOperationsController` | `me` requires one of credential enroll/admin, trust read/request/approve or publication recover; operations requires `model-policy:trust:read` | Actor capabilities and bounded diagnostics only; no secrets, false ready or arbitrary registry writes | Read-only | `AlertSecurityConfigTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/policy-trust/registries`, `/requests`, `/requests/{id}/audit`, `/requests/{id}/deployment`, `/requests/{id}/candidate` | `GovernanceAuthorizationRules` / `PolicyTrustAdministrationController` | `model-policy:trust:read`; affected production entries require production scope | Validated registry metadata, exact stored candidate diff and deployment observations | Read-only | `PolicyTrustCandidateBuilderTest`, `PolicyWebAuthnMongoIntegrationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `POST /api/v1/governance/policy-trust/requests/preview`, `/requests[/challenges]`, `/requests/{id}/reviews[/challenges]` | `GovernanceAuthorizationRules` / `PolicyTrustAdministrationController` | Separate `model-policy:trust:request` / `model-policy:trust:approve`, OIDC and WebAuthn; affected production scope checked in service | Server builds and signs complete exact candidate; approval remains pending protected deployment | Unsafe cookie-backed requests require CSRF | `PolicyTrustCandidateBuilderTest`, `PolicyWebAuthnMongoIntegrationTest` |
| `GET /api/v1/governance/policy-trust/requests/{id}/installation` | `GovernanceAuthorizationRules` / `PolicyTrustAdministrationController` | `model-policy:audit:read`; affected production scope checked in service | Protected export of approved immutable candidate and two historical WebAuthn proofs for offline installer | Read-only, restricted audit artifact | `PolicyWebAuthnMongoIntegrationTest`, `RouteCoverageAgainstMvcMappingsTest` |
| `GET /api/v1/governance/model-policies/webauthn/security-events`, `PUT /webauthn/credentials/{credentialId}/label` | `GovernanceAuthorizationRules` / `PolicyGovernanceController` | `model-policy:credential:enroll`; exact institutional actor owns credential | Own lifecycle history and bounded label only | PUT requires CSRF on cookie-backed sessions | `RouteCoverageAgainstMvcMappingsTest` |
| `/api/v1/audit/**` | `AuditAuthorizationRules` | `AUDIT_READ`, `AUDIT_VERIFY`, `AUDIT_EXPORT`, `AUDIT_DEGRADATION_RESOLVE` | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `/system/trust-level`, `/api/v1/trust/incidents/**` | `TrustAuthorizationRules` | `AUDIT_VERIFY`, trust incident authorities | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `/api/v1/decision-outbox/**`, `/api/v1/regulated-mutations/**`, `/api/v1/outbox/**` | `RecoveryAuthorizationRules` | Recovery, outbox, and audit verification authorities | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `POST /bff/logout` | `BffAuthorizationRules` | Authenticated BFF session | Protected BFF lifecycle | CSRF required in BFF mode | `BffCsrfBoundaryRegressionTest` |
| Unknown `/api/**`, `/api/v1/**`, `/internal/**`, `/governance/**`, `/system/**`, `/bff/**`, `/actuator/**` | `DenyByDefaultAuthorizationRules` | None | Deny by default | Denied before SPA fallback | `DenyByDefaultSecurityTest` |
| Known frontend routes such as `GET /analyst-console` | `SpaFallbackAuthorizationRules` | Public SPA fallback | GET-only fallback | Unsafe methods denied | `SpaFallbackSecurityTest` |
| Any other route | `DenyByDefaultAuthorizationRules` | None | Final deny | Denied | `SecurityMatcherOrderRegressionTest` |

## MVC Controller Coverage

The route ownership docs represent these Spring MVC controllers:

- `AlertController`
- `AnalystSessionController`
- `AuditDegradationController`
- `AuditEventController`
- `AuditEvidenceExportController`
- `AuditIntegrityController`
- `AuditTrustAttestationController`
- `AuditTrustKeysController`
- `ExternalAuditIntegrityController`
- `FraudCaseController`
- `FraudCaseEvidenceSummaryController`
- `FraudCaseEvidenceTimelineController`
- `FraudFeedbackController`
- `FraudCaseWorkQueueSummaryController`
- `GovernanceAdvisoryController`
- `GovernanceAuditController`
- `PromotionReviewReadinessReportController`
- `ModelPromotionApiController`
- `ModelPromotionSignedReviewController`
- `PolicyGovernanceController`
- `OnlinePolicyApproverDirectoryController`
- `PolicyPublicationStatusController`
- `PolicySecurityActorController`
- `PolicySecurityOperationsController`
- `ShadowPerformanceSummaryController`
- `OutboxRecoveryController`
- `RegulatedMutationRecoveryController`
- `ScoredTransactionController`
- `EngineIntelligenceFeedbackController`
- `EngineIntelligenceFeedbackReadController`
- `EngineIntelligenceReadController`
- `SuspiciousTransactionReadController`
- `SystemTrustLevelController`
- `TrustIncidentController`

## PR Metadata

Title: `Security Route Boundary Decomposition and Auth Mode Hardening`

Summary: This work hardens the post-BFF security architecture by decomposing endpoint authorization into domain-owned route groups, preserving a single fail-closed `SecurityFilterChain`, strengthening auth-mode boundary tests, and reducing legacy frontend API-client coupling.

Merge gate: `Security Route Boundary` CI job green, route group tests green, matcher order tests green, BFF/JWT/CSRF boundary tests green, API client boundary guard green, and full CI green.
