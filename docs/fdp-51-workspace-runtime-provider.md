# FDP-51 Workspace Runtime Provider

FDP-51 moves Analyst Console workspace runtime ownership out of `App.jsx` and into a dedicated workspace runtime boundary.
`App.jsx` stays responsible for auth provider bootstrap, top-level routing, the shell layout, and session controls.
Workspace data loading, counters, authority-derived workspace capability flags, and explicit API client creation live in `WorkspaceRuntimeProvider`, `WorkspaceDashboardShell`, and focused workspace hooks.

## Scope

- `WorkspaceRuntimeProvider` owns `createAlertsApiClient({ session, authProvider })`.
- `useWorkspaceRuntime` exposes `session`, `authProvider`, `apiClient`, `workspaceSessionResetKey`, read capability flags, and `runtimeStatus`.
- Workspace hooks keep accepting explicit test clients while defaulting to runtime context in application composition.
- Workspace counters move to `useWorkspaceCounters` with abort, request sequencing, partial failure reporting, stale retained values, and session-boundary clearing.
- Counter UI visibly reports degraded and stale states without converting missing authority or failed counters to zero.
- `WorkspaceDashboardShell` owns workspace hook orchestration, refresh behavior, details-page runtime wiring, and governance audit refresh behavior.
- FDP-49/FDP-50 API client and raw-fetch guardrails remain mandatory.

## Non-Goals

- No backend endpoint, auth, lifecycle, controller, or read-model changes.
- No new business endpoint.
- No fraud-case lifecycle change.
- No mutation expansion, export workflow, bulk workflow, assignment workflow, or mass action.
- No Kafka, outbox, finality, distributed ACID, or idempotency change.
- No token, CSRF, or cursor material is moved into browser storage or generic application state.

## Runtime Contract

- Authenticated workspace runtime creates one explicit API client for the current session/auth provider object.
- A changed session object, including the same user with changed auth material or authorities, recreates the client and changes the reset key.
- Unauthenticated or disabled runtime exposes `apiClient: null` and `runtimeStatus: "disabled"`.
- Workspace hooks abort in-flight requests and ignore stale responses on session/client/workspace switches.
- Workspace counters clear on logout and session boundary reset.
- Partial counter failures set `degraded`; retained previous values are marked `stale` and shown as last-known values.
- Missing authority makes a counter unavailable, not zero.

## SOLID / ACID Alignment

- Single responsibility: session bootstrap remains in `App.jsx`; workspace runtime state is isolated in provider/shell/hooks.
- Open/closed: new workspace panels should consume runtime context or explicit clients without reintroducing global API state.
- Interface segregation: hooks depend on the API methods they call through the explicit client, not on auth internals.
- Dependency inversion: workspace data hooks accept an injected client for tests and receive the production client from runtime context.
- ACID claims are intentionally out of scope. FDP-51 improves frontend runtime isolation and consistency of visible state, not transactional guarantees.

## Merge Gate

- `npm test -- --run`
- `npm run build`
- `npm run check:api-client-boundary:fdp50`
- `npm run check:scope:fdp51`
- CI job: `FDP-51 Analyst Console Workspace Runtime`
- CI must reject `.skip` and `.only` in FDP-51 runtime, counter, workspace, app, and API boundary tests.
- `documents/` remains uncommitted.
