# FDP-48: Analyst Console Request Lifecycle & Workspace Decomposition

## Summary

FDP-48 hardens the Analyst Console frontend request lifecycle and reduces `App.jsx` ownership of workspace-specific data.

## Backend Scope

- Adds an optional server-backed BFF session mode for the Docker/OIDC Analyst Console.
- `GET /api/v1/session` exposes only normalized session identity, roles, authorities, and CSRF metadata.
- OIDC login is handled by `alert-service` server-side session auth when `APP_SECURITY_BFF_ENABLED=true`.
- Existing bearer JWT resource-server behavior remains available for direct API clients and non-BFF modes.
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

## Non-Goals

- No export.
- No bulk actions.
- No assignment workflow.
- No optimistic mutation UI.
- No summary snapshot-consistency claim.
- No claim that browser DevTools can hide request headers for SPA bearer mode; the BFF mode avoids browser-side bearer API calls instead.

## Merge Gate

- Browser API calls in Docker/OIDC BFF mode must not include an `Authorization` header.
- Mutating cookie-backed BFF requests must use CSRF metadata from `/api/v1/session`.
- Aborted requests do not show errors.
- Stale responses cannot overwrite newer state.
- Workspace changes abort irrelevant requests.
- Cursor remains opaque and is not rendered, stored, or logged.
- Workspace-specific failures remain local to the active workspace.
- CI must be green on the current head SHA.
