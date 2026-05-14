# FDP-49 Endpoint Authorization Map

FDP-49 is security architecture hardening only. It decomposes authorization rules and configurers while preserving one `SecurityFilterChain` to avoid filter-order ambiguity.

No new business endpoints, fraud-case lifecycle changes, idempotency changes, `RegulatedMutationCoordinator` changes, Kafka/outbox/finality changes, export workflow, bulk action workflow, assignment workflow, or UI feature work are part of FDP-49.

## Adding a new backend endpoint safely

Every backend endpoint must be explicitly owned by a route group. New endpoints under `/api/**`, `/api/v1/**`, `/governance/**`, `/system/**`, `/bff/**`, and `/actuator/**` are denied by default until a route group allowlists them. This is the FDP-49 deny by default rule.

Never add broad `permitAll` rules such as `/api/**`, `/api/v1/**`, `/governance/**`, `/system/**`, or `/bff/**`. Every public endpoint must explain why it is public.

The SPA fallback must remain GET-only and must not catch backend-looking paths. Unsafe cookie-backed routes require CSRF. Stateless bearer APIs may bypass CSRF only when no `JSESSIONID` is present, and they still require valid JWT authentication and RBAC authorization.

Every endpoint addition must include a correct-authority test, wrong-authority test, anonymous test, unknown sibling route test, and unsafe method/CSRF test when applicable.

## Auth observability boundaries

Auth/session/security metrics are operational signals, not audit evidence. They must not place raw tokens, session IDs, user IDs, authorities, role values, provider groups, raw OIDC claim values, provider logout URLs, or query strings in metric labels.

BFF session, logout, CSRF, and OIDC mapping events use low-cardinality outcome labels. Sensitive read audit remains separate from operational metrics.

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

FDP-49 does not certify enterprise IAM readiness. BFF mode is a safer browser auth foundation, not a replacement for production IAM governance. The CSRF token is not authentication material. Backend Spring Security remains the enforcement source.

## Route Ownership

| Route family | Owner | Authority | Classification | CSRF behavior | Expected test |
| --- | --- | --- | --- | --- | --- |
| `/actuator/health/**`, `/actuator/info` | `PublicTechnicalAuthorizationRules` | Public technical endpoint | Public | Safe methods only | `SecurityMatcherOrderRegressionTest` |
| Static frontend assets and root document | `PublicTechnicalAuthorizationRules` | Public static content | Public | Safe methods only | `SecurityMatcherOrderRegressionTest` |
| `GET /api/v1/session` | `SessionAuthorizationRules` | Public bootstrap | Public, no-store, token-free | Safe method only | `BffSessionSecurityIntegrationTest` |
| `/oauth2/**`, `/login/oauth2/**`, `/error` | `SessionAuthorizationRules` | OAuth/session bootstrap | Public auth lifecycle | Framework controlled | `SecurityMatcherOrderRegressionTest` |
| `/api/v1/alerts/**` | `AlertAuthorizationRules` | `ALERT_READ`, `ASSISTANT_SUMMARY_READ`, `ALERT_DECISION_SUBMIT` | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `/api/v1/fraud-cases/**`, `/api/fraud-cases/**` | `FraudCaseAuthorizationRules` | `FRAUD_CASE_READ`, `FRAUD_CASE_AUDIT_READ`, `FRAUD_CASE_UPDATE` | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `GET /api/v1/transactions/scored` | `TransactionAuthorizationRules` | `TRANSACTION_MONITOR_READ` | Protected | Safe method only | `AuthorizationRulesCoverageTest` |
| `/governance/advisories/**` | `GovernanceAuthorizationRules` | `TRANSACTION_MONITOR_READ`, `GOVERNANCE_ADVISORY_AUDIT_WRITE` | Protected or denied | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `/api/v1/audit/**` | `AuditAuthorizationRules` | `AUDIT_READ`, `AUDIT_VERIFY`, `AUDIT_EXPORT`, `AUDIT_DEGRADATION_RESOLVE` | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `/system/trust-level`, `/api/v1/trust/incidents/**` | `TrustAuthorizationRules` | `AUDIT_VERIFY`, trust incident authorities | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `/api/v1/decision-outbox/**`, `/api/v1/regulated-mutations/**`, `/api/v1/outbox/**` | `RecoveryAuthorizationRules` | Recovery, outbox, and audit verification authorities | Protected | Unsafe cookie-backed requests require CSRF | `AuthorizationRulesCoverageTest` |
| `POST /bff/logout` | `BffAuthorizationRules` | Authenticated BFF session | Protected BFF lifecycle | CSRF required in BFF mode | `BffCsrfBoundaryRegressionTest` |
| Unknown `/api/**`, `/api/v1/**`, `/governance/**`, `/system/**`, `/bff/**`, `/actuator/**` | `DenyByDefaultAuthorizationRules` | None | Deny by default | Denied before SPA fallback | `DenyByDefaultSecurityTest` |
| Known frontend routes such as `GET /analyst-console` | `SpaFallbackAuthorizationRules` | Public SPA fallback | GET-only fallback | Unsafe methods denied | `SpaFallbackSecurityTest` |
| Any other route | `DenyByDefaultAuthorizationRules` | None | Final deny | Denied | `SecurityMatcherOrderRegressionTest` |

## PR Metadata

Title: `FDP-49: Security Route Boundary Decomposition & Auth Mode Hardening`

Summary: FDP-49 hardens the post-BFF security architecture by decomposing endpoint authorization into domain-owned route groups, preserving a single fail-closed `SecurityFilterChain`, strengthening auth-mode boundary tests, and reducing legacy frontend API-client coupling.

Merge gate: `FDP-49 Security Route Boundary Hardening` CI job green, route group tests green, matcher order tests green, BFF/JWT/CSRF boundary tests green, API client boundary guard green, and full CI green.
