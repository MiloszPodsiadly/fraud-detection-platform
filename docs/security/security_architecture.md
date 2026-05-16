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
- JWT resource server: stateless bearer API mode with configurable claim-to-authority mapping.
- Local demo auth: local/dev/test/docker-local only. It is disabled by default and must not be treated as a production identity provider.

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

## Limitations

The repository provides a security implementation foundation, not a complete bank operating model. Production IAM governance, provider tenancy, secret rotation, certificate automation, ingress hardening, network policy, SIEM integration, WORM retention, and regulatory certification remain deployment and operating-environment responsibilities unless implemented explicitly in this repository.

Audit and regulated mutation controls are local service contracts. They are not distributed ACID. They do not provide exactly-once processing across Kafka, MongoDB, external audit storage, and downstream consumers.

## Review Checklist

- New backend endpoints are present in [Endpoint authorization map](endpoint_authorization_map.md).
- Route ownership is registered in `SecurityRouteOwnershipRegistry`.
- Positive, anonymous, wrong-authority, unknown sibling, and unsafe-method/CSRF tests cover the route family.
- UI auth-sensitive calls go through `createAlertsApiClient({ session, authProvider })`.
- New metrics use bounded low-cardinality labels.
- New service-to-service calls use `InternalServiceAuthHeaders` and the shared internal HTTP client boundary.
