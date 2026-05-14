# FDP-50 Frontend API Client Boundary

FDP-50 hardens the Analyst Console runtime boundary by replacing module-global API session state in auth-sensitive flows with explicit workspace-scoped API client instances.

## Scope

- Primary API path: `createAlertsApiClient({ session, authProvider })`.
- Workspace hooks receive an explicit `apiClient` from the App/workspace shell.
- The client instance closes over the session/auth provider chosen at the workspace boundary.
- Logout, unauthenticated state, and session/user/provider switches must disable or clear sensitive workspace state and prevent stale responses from committing.
- FDP-50 removes unused legacy wrappers from `alertsApi.js`; auth-sensitive workspace code must not reintroduce or import them.
- FDP-50 intentionally keeps `App.jsx` as the composition root. A future frontend architecture branch may extract `WorkspaceRuntimeProvider` or `useWorkspaceRuntime`, but FDP-50 only hardens the API client boundary.

## Non-Goals

- No backend endpoints.
- No backend auth mode changes.
- No business mutations beyond existing UI calls.
- No fraud-case lifecycle changes.
- No idempotency changes.
- No Kafka, outbox, or finality changes.
- No export, bulk action, assignment, or product workflow expansion.

## Auth Boundaries

- BFF mode uses same-origin cookies and CSRF headers from the BFF session provider. It must not emit `Authorization`.
- JWT/OIDC/direct mode keeps bearer token behavior explicit in the auth provider.
- Demo mode remains local/dev compatibility only.
- CSRF, cursors, and bearer tokens must not be moved into generic app state or browser storage by workspace hooks.

## Guardrails

- raw fetch is forbidden outside API/auth bootstrap code.
- Auth-sensitive UI may import `createAlertsApiClient` and safe helpers only; it may not import or reintroduce default wrappers.
- `setApiSession` and module-global API session state are not valid dependencies for workspace hooks.
- The FDP-50 CI gate runs frontend tests, build, API boundary guard, and scope guard.
