# FDP-48: Analyst Console Request Lifecycle & Workspace Decomposition

## Summary

FDP-48 hardens the Analyst Console frontend request lifecycle and reduces `App.jsx` ownership of workspace-specific data.

## Backend Scope

- Adds an optional server-backed BFF session mode for the Docker/OIDC Analyst Console.
- `GET /api/v1/session` exposes only normalized session identity, roles, authorities, and CSRF metadata.
- `GET /api/v1/session` returns a low-cardinality `sessionStatus` value (`AUTHENTICATED` or `ANONYMOUS`) for UI lifecycle decisions.
- CSRF metadata in `/api/v1/session` is request metadata for the cookie-backed BFF path. It is not an access token, refresh token, ID token, or bearer credential.
- `GET /api/v1/session` is returned with `Cache-Control: no-store` and fails closed when an authenticated principal has no usable subject.
- OIDC login is handled by `alert-service` server-side session auth when `APP_SECURITY_BFF_ENABLED=true`.
- Existing bearer JWT resource-server behavior remains available for direct API clients and non-BFF modes.
- Cookie-backed BFF mutations require CSRF; adding `Authorization: Bearer ...` to a session-cookie request does not make it stateless.
- BFF logout validates configured provider and post-logout redirect origins from allowlists and clears cookies only through the backend logout endpoint.
- Backend logout validation is the source of truth. The frontend treats `/bff/logout` response URLs as backend-vetted and only rejects empty, malformed, protocol-relative, or dangerous-scheme URL forms.
- BFF mode default-denies unknown backend-looking routes. Only explicit business endpoints, public health/session/OAuth routes, static assets, and narrow SPA fallback routes are allowed.
- BFF `client-id` must be configured outside local/dev/test profiles; local defaulting exists only for developer stacks.
- No lifecycle mutation changes.
- No idempotency, coordinator, outbox, Kafka, or finality changes.

## Frontend Scope

- API client read calls accept `AbortSignal`.
- Intentional `AbortError` cancellations are ignored by hooks and are not rendered as user-facing network errors.
- Workspace URL/popstate state is owned by `useWorkspaceRoute`.
- Fraud Case work queue and summary requests abort superseded in-flight requests.
- Alert, scored transaction, governance queue, and governance analytics reads are owned by workspace-scoped hooks.
- Fraud Case Work Queue UI is split into presentational components.
- Fraud-case reads are gated when session authorities clearly show missing fraud-case read access.
- Docker/OIDC UI uses `VITE_AUTH_PROVIDER=bff`, so React `fetch` calls do not attach `Authorization: Bearer ...`.
- The frontend stores neither bearer tokens nor CSRF tokens in localStorage/sessionStorage.
- BFF requests use same-origin credentials explicitly. Mutating requests attach only the CSRF header supplied by `/api/v1/session`.
- The BFF provider consumes backend `sessionStatus` as the session lifecycle source. Frontend roles and authorities are display/capability hints, not authorization enforcement.
- BFF logout is fail-closed: failed logout responses, missing CSRF, or untrusted redirect URLs do not clear local session state or navigate.
- Workspace hooks clear loaded data when the session, authority gate, or workspace enablement no longer permits the read.
- Production-like frontend builds default to BFF auth and reject implicit demo auth unless explicitly allowed for local override.

## Non-Goals

- No export.
- No bulk actions.
- No assignment workflow.
- No optimistic mutation UI.
- No summary snapshot-consistency claim.
- No claim that browser DevTools can hide request headers for SPA bearer mode; the BFF mode avoids browser-side bearer API calls instead.
- No claim that FDP-48 is complete enterprise IAM hardening.

## Deployment Boundary

FDP-48 provides a BFF session-auth foundation for the Docker/OIDC and production-like browser flow. It is not a complete enterprise IAM hardening package.

Production deployment requires environment-specific configuration for:

- allowed provider logout origins
- allowed post-logout redirect origins
- issuer, client, and client-secret material
- HTTPS-only ingress
- Secure cookie behavior
- SameSite policy
- reverse proxy forwarded headers
- session timeout policy
- IdP operational monitoring

Local/dev/test profiles may allow localhost and local defaults. Production or bank profiles must not use local/dev/test profile escape hatches. Direct SPA OIDC remains compatibility/local mode, not the Docker/OIDC BFF default.

## CSRF Semantics

`GET /api/v1/session` is intentionally public for session bootstrap and may return CSRF metadata for anonymous sessions. CSRF metadata is not authentication material: it is not an access token, refresh token, ID token, JWT, or bearer credential. The CSRF token is browser-readable by design in this SPA double-submit/header pattern, and it does not protect against XSS.

The BFF path relies on same-origin cookies plus a custom CSRF header for unsafe requests, together with browser and CORS constraints. `/api/v1/session` must remain `no-store` and must never expose bearer tokens, raw claims, profile, email, provider groups, or a full OIDC payload.

Do not overclaim:

- do not call the CSRF token secret
- do not claim BFF is full production IAM hardening
- do not claim direct SPA OIDC hides bearer headers

## Merge Gate

- Browser API calls in Docker/OIDC BFF mode must not include an `Authorization` header.
- Mutating cookie-backed BFF requests must use CSRF metadata from `/api/v1/session`.
- Aborted requests do not show errors.
- Stale responses cannot overwrite newer state.
- Workspace changes abort irrelevant requests.
- Cursor remains opaque and is not rendered, stored, or logged.
- Workspace-specific failures remain local to the active workspace.
- CI must be green on the current head SHA.
- Required FDP-48 CI job: `FDP-48 BFF Session & Request Lifecycle`.
- The FDP-48 CI job must fail when required backend reports are missing and when required frontend tests are skipped, focused, missing from the JUnit report, failed, or not run.

## Required FDP-48 Checks

Backend:

```bash
mvn -B -pl alert-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=BffSessionSecurityIntegrationTest,BffSecurityPropertiesTest,BffLogoutSuccessHandlerTest,OidcAnalystAuthoritiesMapperTest,AlertSecurityConfigJwtEnabledTest" test
```

Frontend:

```bash
cd analyst-console-ui
npm test -- --run src/auth/authProvider.test.js src/api/alertsApi.test.js src/fraudCases/useFraudCaseWorkQueue.test.js src/fraudCases/useFraudCaseWorkQueueSummary.test.js src/workspace/workspaceDataHooks.test.js src/workspace/useWorkspaceRoute.test.js
npm run build
```

## Completed Hardening

- `alertsApi.js` uses `createAlertsApiClient({ session, authProvider })`; workspace requests no longer share module-level session/provider state.
- Security configuration is split into endpoint authorization rules, BFF session setup, JWT resource-server setup, and demo-auth setup while keeping one explicit `SecurityFilterChain`.
- BFF production hardening remains deployment-specific and documented as required deployment configuration, not implemented by local defaults.
- Direct SPA OIDC remains a local compatibility mode. Production-like browser deployments should use BFF auth.
- Governance hook cancellation parity is covered by the FDP-48 workspace hook tests and should stay mandatory in CI.

## Final Audit Notes

- No browser-side BFF API request should include an `Authorization` header.
- `/api/v1/session` response bodies must not contain access, refresh, or ID tokens.
- CSRF tokens are transient request metadata, not localStorage/sessionStorage state.
- Direct bearer API clients remain supported through the existing resource-server path.
- This branch does not claim browser DevTools can hide headers. It removes browser-side bearer API calls in BFF mode.
- Direct SPA OIDC remains a local/compatibility path. Production-like browser deployments should use the cookie-backed BFF pattern.
