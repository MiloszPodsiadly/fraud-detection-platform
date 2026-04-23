# Security Foundation v1

Single technical reference for the analyst workflow security foundation in `fraud-detection-platform`.

This document consolidates the previous RBAC, local demo auth, audit logging, frontend session, JWT/OIDC migration, ADR, plan, and review notes into one reviewer-friendly entry point. `README.md` stays the high-level project entry point.

## Current State

Security Foundation v1 is implemented for the analyst workflow owned by `alert-service` and consumed by `analyst-console-ui`.

Backend:

- Spring Security protects analyst APIs under `/api/v1/**`.
- Health and info actuator endpoints remain public for local orchestration.
- Local/demo auth is disabled by default and can only be enabled in `local`, `dev`, `docker-local`, or `test` profiles.
- Authorization checks authorities, not role names.
- Write actions use authenticated principal identity as the actor source of truth.
- Audit logging v1 records analyst write actions through structured logs.
- JWT/OIDC extension points exist, but production token validation is not implemented yet.

Frontend:

- The UI uses a stable session contract: `userId`, `roles`, and `authorities`.
- The local demo session provider is isolated in `src/auth/demoSession.js`.
- Security states distinguish unauthenticated, forbidden, and missing-permission action states.
- Frontend action gating is UX only; backend authorization remains authoritative.
- A small contract guard test checks that frontend authority names stay aligned with backend `AnalystAuthority` constants.

## Scope

In scope:

- local/dev authentication path
- role and authority matrix
- endpoint authorization for analyst APIs
- 401/403 JSON error contract
- principal-based actor identity for write paths
- audit logging for write actions
- frontend session awareness and action gating
- migration notes for JWT/OIDC

Out of scope:

- production JWT/OIDC validation
- service-to-service authentication
- shared security module extraction
- persistent audit store
- read-access audit
- full production login flow in the frontend

## Architecture Decision

Decision:

- Keep the authorization model inside `alert-service` for v1.
- Use roles for local personas and authorities for enforcement.
- Keep demo auth behind a local/dev adapter.
- Keep controllers and business services independent from demo header names and future identity-provider claim names.
- Use the authenticated principal as the actor source of truth when available.
- Make JWT/OIDC replace identity extraction, not endpoint authorization rules.

Rationale:

- Authorities are more stable than role names for endpoint enforcement.
- A small internal principal model keeps audit, service code, and future OIDC mapping aligned.
- Local demo auth lets the UI and backend exercise real 401/403 and permission states before production auth is added.
- Keeping v1 in `alert-service` avoids premature shared-module extraction before the reusable boundaries are proven.

## Internal Security Types

Backend implementation lives under `alert-service`:

- `com.frauddetection.alert.security.authorization.AnalystAuthority`
- `com.frauddetection.alert.security.authorization.AnalystRole`
- `com.frauddetection.alert.security.principal.AnalystPrincipal`
- `com.frauddetection.alert.security.principal.CurrentAnalystUser`
- `com.frauddetection.alert.security.principal.AnalystActorResolver`
- `com.frauddetection.alert.security.auth.DemoAuthFilter`
- `com.frauddetection.alert.security.config.AlertSecurityConfig`
- `com.frauddetection.alert.security.config.DemoAuthSecurityConfig`
- `com.frauddetection.alert.audit.AuditService`

Frontend implementation:

- `analyst-console-ui/src/auth/session.js`
- `analyst-console-ui/src/auth/demoSession.js`
- `analyst-console-ui/src/auth/securityErrors.js`
- `analyst-console-ui/src/components/SessionBadge.jsx`
- `analyst-console-ui/src/components/SecurityStatePanels.jsx`

## RBAC Model

Roles describe analyst personas. Authorities are the backend authorization contract.

### Authorities

| Authority | Capability |
| --- | --- |
| `alert:read` | List alerts and get alert details. |
| `assistant-summary:read` | Generate or read assistant case summary. |
| `alert:decision:submit` | Submit analyst decision for an alert. |
| `fraud-case:read` | List fraud cases and get case details. |
| `fraud-case:update` | Update fraud case decision/status fields. |
| `transaction-monitor:read` | Read scored transaction monitoring data. |

### Roles

| Role | Intent | Authorities |
| --- | --- | --- |
| `READ_ONLY_ANALYST` | Analyst queue visibility without write actions. | `alert:read`, `assistant-summary:read`, `fraud-case:read`, `transaction-monitor:read` |
| `ANALYST` | Standard analyst who can review alerts and submit alert decisions. | `READ_ONLY_ANALYST` authorities plus `alert:decision:submit` |
| `REVIEWER` | Senior analyst/reviewer who can also update fraud cases. | `ANALYST` authorities plus `fraud-case:update` |
| `FRAUD_OPS_ADMIN` | Fraud operations lead/admin with full v1 access. | all v1 authorities |

### Endpoint Matrix

| Endpoint | Required Authority | Notes |
| --- | --- | --- |
| `GET /api/v1/alerts` | `alert:read` | Alert queue read. |
| `GET /api/v1/alerts/{alertId}` | `alert:read` | Alert details can contain decision history and evidence. |
| `GET /api/v1/alerts/{alertId}/assistant-summary` | `assistant-summary:read` | Separate authority because summaries may later call controlled AI infrastructure. |
| `POST /api/v1/alerts/{alertId}/decision` | `alert:decision:submit` | Write action; audit in v1. |
| `GET /api/v1/fraud-cases` | `fraud-case:read` | Case queue read. |
| `GET /api/v1/fraud-cases/{caseId}` | `fraud-case:read` | Case details read. |
| `PATCH /api/v1/fraud-cases/{caseId}` | `fraud-case:update` | Write action; audit in v1. |
| `GET /api/v1/transactions/scored` | `transaction-monitor:read` | Separate from alert read because monitor data may grow beyond alert queue use cases. |

## Local Demo Auth

Demo auth is a local development tool, not a production authentication path.

It requires both:

- `APP_SECURITY_DEMO_AUTH_ENABLED=true`
- active profile `local`, `dev`, `docker-local`, or `test`

If demo auth is enabled outside those profiles, `alert-service` rejects startup.

When demo auth is disabled, `X-Demo-*` headers are ignored and do not create an implicit session.

### Headers

| Header | Required | Example | Notes |
| --- | --- | --- | --- |
| `X-Demo-User-Id` | yes, to authenticate | `analyst-1` | Missing header means anonymous request. |
| `X-Demo-Roles` | no | `ANALYST` | Comma-separated `AnalystRole` names. Defaults to `READ_ONLY_ANALYST` when user id is present. |
| `X-Demo-Authorities` | no | `fraud-case:update` | Comma-separated v1 authority names for local override/testing. Unknown values are rejected. |

Example:

```bash
curl \
  -H "X-Demo-User-Id: analyst-1" \
  -H "X-Demo-Roles: ANALYST" \
  http://localhost:8085/api/v1/alerts
```

Expected behavior:

- unauthenticated requests return HTTP 401
- authenticated users without the required authority return HTTP 403
- invalid demo roles or authorities return normalized security errors
- health and info actuator endpoints remain public for local orchestration

## Actor Identity

Write request DTOs still accept `analystId` for compatibility. For secured requests, the authenticated principal wins:

- persisted analyst id uses principal user id
- emitted decision event uses principal user id
- audit actor uses principal user id and authorities
- request/principal mismatch is logged as a warning diagnostic

The request-body actor remains only as a compatibility fallback for paths without an authenticated principal, such as lower-level service tests.

## Audit Logging v1

Audit Logging v1 records security-relevant analyst write operations in `alert-service`.

Audited actions:

- `SUBMIT_ANALYST_DECISION` for `ALERT`
- `UPDATE_FRAUD_CASE` for `FRAUD_CASE`

Event model:

- actor user id
- actor roles and authorities when an authenticated principal is available
- action type
- resource type
- resource id
- timestamp
- correlation id when available
- outcome: `SUCCESS`, `REJECTED`, or `FAILED`
- optional failure reason category

Sensitive data intentionally excluded:

- analyst decision reason
- decision tags
- model feature snapshots
- transaction details
- customer data
- full request payloads

Implementation:

- Controllers do not contain audit logic.
- Write-path services call `AuditService` after persistence and domain publication complete where applicable.
- `AuditService` builds an `AuditEvent` from the current security principal and falls back to request actor only when no authenticated principal exists.
- `AuditEventPublisher` is the extension point.
- `StructuredAuditEventPublisher` writes structured key-value logs through SLF4J.

Future sinks can be added behind `AuditEventPublisher`:

- Kafka audit topic
- MongoDB audit collection
- SIEM forwarder
- retention and masking policies
- read-access auditing if required

## Frontend Security UX

The analyst console sends the local demo auth headers expected by `alert-service`:

- `X-Demo-User-Id`
- `X-Demo-Roles`
- `X-Demo-Authorities`

Default local session:

- user id: `analyst.local`
- role: `REVIEWER`

Optional Vite overrides:

```bash
VITE_DEMO_USER_ID=analyst-1
VITE_DEMO_ROLES=ANALYST
```

Session behavior:

- selecting `Unauthenticated` removes demo auth headers and should produce HTTP 401 states from protected API calls
- selecting `READ_ONLY_ANALYST` keeps reads enabled but disables write actions requiring `alert:decision:submit` or `fraud-case:update`
- unavailable write actions show an inline permission notice with the required authority
- HTTP 401 shows a session-required panel
- HTTP 403 shows an access-denied panel

This is intentionally not a login flow. Later OIDC work should replace `src/auth/demoSession.js`, while preserving `src/auth/session.js` and the UI security states.

## 401/403 Error Contract

Security failures use the same `ApiErrorResponse` shape as other `alert-service` API errors:

```json
{
  "timestamp": "2026-04-23T09:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication is required.",
  "details": []
}
```

Frontend handling:

- HTTP 401 means no valid session context exists.
- HTTP 403 means the session is valid but lacks the required authority.
- `details` is intentionally empty for security failures.
- The frontend should not depend on backend exception text.

## JWT/OIDC Migration Path

Security Foundation v1 keeps local/demo authentication behind adapter boundaries. JWT/OIDC should replace identity resolution, not rewrite controller authorization, current-user access, audit logging, or frontend security states.

Recommended migration:

1. Add `spring-boot-starter-oauth2-resource-server` to `alert-service`.
2. Add environment-specific issuer/JWK configuration.
3. Implement a JWT authentication converter that maps external claims, groups, or scopes into existing `AnalystAuthority` strings.
4. Emit an `AnalystPrincipal` or compatible principal from the converter.
5. Reuse `AnalystAuthenticationFactory` if a custom authentication token is assembled manually, or keep authority mapping equivalent if Spring builds the token.
6. Keep `DemoAuthSecurityConfig` disabled outside local/dev/test and remove demo auth from non-local deployment manifests.
7. Keep the endpoint authorization matrix unchanged.
8. Update `analyst-console-ui` to obtain session context from the OIDC client, then map it into the same user/roles/authorities shape.

Review decisions before OIDC:

- Which external claim is the stable analyst user id?
- Do roles come from groups, scopes, app roles, or an authorization service?
- Should `FRAUD_OPS_ADMIN` be granted directly by the identity provider or composed inside `alert-service`?
- When can request DTO `analystId` be removed or ignored completely?

## Review Focus

Reviewers should check:

- endpoint matrix matches expected analyst workflow boundaries
- role-to-authority mapping is correct for all four v1 roles
- demo auth guardrails prevent accidental non-local use
- principal identity wins over payload actor fields on write paths
- audit payload excludes enough sensitive business data
- frontend action gating is clear but not treated as enforcement
- JWT/OIDC migration can reuse the current principal and authority shape

## Known Limitations

- No production JWT/OIDC validation flow yet.
- No service-to-service authentication yet.
- No persistent audit sink yet; v1 logs structured events only.
- No read-access audit yet.
- No full login flow in the frontend.
- Backend and frontend currently duplicate authority names.
- `analystId` is still accepted in write DTOs for compatibility.
- `anyRequest().permitAll()` intentionally leaves non-API local routes public; protected business APIs are under `/api/v1/**`.

## Suggested Next Steps

1. Implement JWT/OIDC Resource Server support behind the existing adapter boundary.
2. Remove request-body actor fields once API compatibility allows it.
3. Add a durable audit sink if retention requirements appear.
4. Decide whether a shared security module is justified after another service needs the same model.
5. Add service-to-service authentication and transport controls for non-local deployment.
