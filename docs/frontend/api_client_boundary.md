# Frontend API Client Boundary

Status: current frontend architecture source of truth.

## Scope

This document defines how auth-sensitive Analyst Console code calls backend APIs. It is a frontend architecture
boundary, not a backend authorization boundary, not a product workflow specification, and not a replacement for
server-side RBAC.

FDP-50 removed unused legacy API wrappers and keeps guard coverage so they are not reintroduced.

## Approved Pattern

| Layer | Responsibility |
| --- | --- |
| Workspace/session boundary | Create the client with `createAlertsApiClient({ session, authProvider })`. |
| Hooks, providers, detail pages | Receive `apiClient` explicitly. |
| Hooks | Call `apiClient` methods and handle loading, abort, stale response, and missing-client states. |
| Components | Render state and dispatch user actions; do not call `fetch`. |

## Forbidden Patterns

- Raw `fetch` in components, workspace hooks, fraud-case hooks, or pages.
- Default wrappers imported from `alertsApi.js` in auth-sensitive UI.
- `setApiSession` in hooks.
- Browser storage for CSRF tokens, opaque cursors, bearer tokens, or session tokens.
- Mixing BFF, JWT/OIDC, or demo client state across one client instance.

## Auth Mode Rules

| Mode | Client behavior |
| --- | --- |
| BFF | Use cookies plus CSRF and same-origin credentials; do not emit `Authorization`. |
| JWT/OIDC | Use explicit `Authorization: Bearer <token>` from the provider. |
| Demo | Use local/dev-only demo headers from the demo provider. |

## Logout And Session Switch

- Old clients must not be reused after logout, user switch, or provider switch.
- Workspace state must be cleared or isolated when the session becomes unauthenticated.
- Stale responses from an old client must not overwrite current UI state.

## Testing Checklist For Workspace Hooks

- Use an explicit mocked `apiClient`.
- Cover disabled and missing-client behavior.
- Cover `AbortError` without turning it into a generic network error.
- Cover stale response ordering.
- Cover logout/session switch clearing or isolation.
- Do not mock module-global `alertsApi` wrapper state.

## Guardrails

The frontend architecture gate runs:

- `npm run check:api-client-boundary`
- `npm run check:api-client-boundary:fdp49`
- `npm run check:api-client-boundary:fdp50`
- frontend unit tests covering API client boundary behavior

These guards prevent raw fetch, default wrapper reintroduction, dynamic wrapper imports, and route strings outside the
API/auth bootstrap boundary.
