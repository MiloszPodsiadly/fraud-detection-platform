# Frontend API Client Boundary

## How to Call Backend APIs from the Analyst Console

Approved pattern:

1. Create the API client at the workspace/session boundary with `createAlertsApiClient({ session, authProvider })`.
2. Pass `apiClient` into hooks, providers, and detail pages that need backend data.
3. Hooks call `apiClient` methods.
4. Components do not call `fetch`.

Forbidden patterns:

- raw fetch in components, workspace hooks, fraud-case hooks, or pages.
- default wrappers imported from `alertsApi.js` in auth-sensitive UI.
- `setApiSession` in hooks.
- Storing CSRF tokens, opaque cursors, bearer tokens, or session tokens in `localStorage` or `sessionStorage`.
- Mixing BFF, JWT/OIDC, or demo client state across one client instance.

Auth mode notes:

- BFF: cookies plus CSRF, same-origin credentials, no `Authorization`.
- JWT/OIDC: explicit `Authorization: Bearer <token>` from the provider.
- Demo: local/dev only headers from the demo provider.

Logout and session switch:

- Old clients must not be reused after logout, user switch, or provider switch.
- Workspace state must be cleared or isolated when the session becomes unauthenticated.
- Stale responses from an old client must not overwrite current UI state.

Testing checklist for new workspace hooks:

- Use an explicit mocked `apiClient`.
- Cover disabled and missing-client behavior.
- Cover `AbortError` without generic network errors.
- Cover stale response ordering.
- Cover logout/session switch clearing or isolation.
- Do not mock module-global `alertsApi` wrapper state.
