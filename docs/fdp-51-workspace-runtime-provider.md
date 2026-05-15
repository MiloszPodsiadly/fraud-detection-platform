# FDP-51 Workspace Runtime Provider

FDP-51 creates the first explicit Analyst Console workspace runtime layer and moves runtime orchestration out of `App.jsx`.
`App.jsx` stays responsible for auth provider bootstrap, top-level routing, the shell layout, and session controls.
Workspace data loading, counters, authority-derived workspace capability flags, and explicit API client creation live in `WorkspaceRuntimeProvider`, `WorkspaceDashboardShell`, and workspace-specific runtime hooks.

## Scope

- `WorkspaceRuntimeProvider` owns `createAlertsApiClient({ session, authProvider })`.
- `useWorkspaceRuntime` exposes `session`, `authProvider`, `apiClient`, `workspaceSessionResetKey`, read capability flags, and `runtimeStatus`.
- Workspace hooks keep accepting explicit test clients while defaulting to runtime context in application composition.
- Auth-sensitive workspace hooks may receive an explicit test client or consume the `WorkspaceRuntimeProvider` client.
  They must not import default API wrappers, raw fetch backend URLs, or module-global session state.
- Explicit hook client props are for tests, stories, and standalone hook verification only; production composition goes through `WorkspaceRuntimeProvider`.
- Workspace counters move to `useWorkspaceCounters` with abort, request sequencing, partial failure reporting, stale retained values, and session-boundary clearing.
- Counter UI visibly reports degraded and stale states without converting missing authority or failed counters to zero.
- `WorkspaceDashboardShell` wires workspace-specific runtime hooks into the page component.
- New business workflow logic belongs in workspace-specific containers or hooks, not in the shell.
- `AlertsListPage` renders the current workspace presentation surfaces and should not receive new workflow responsibilities.
- Analyst workspace reads live in `useAnalystWorkspaceRuntime`.
- Alert and transaction workspace reads live in `useTransactionWorkspaceRuntime`.
- Governance advisory, analytics, and audit workflow wiring live in `useGovernanceWorkspaceRuntime`.
- Detail-page routing lives in `WorkspaceDetailRouter`.
- Refresh routing is isolated in `useWorkspaceRefreshController`; governance audit writes are isolated in `useGovernanceAuditWorkflow`.
- FDP-49/FDP-50 API client and raw-fetch guardrails remain mandatory.

## Runtime Boundary, Not Security Boundary

- `WorkspaceRuntimeProvider` is a frontend runtime/UX boundary, not an authorization or security enforcement boundary.
- Backend authorization remains authoritative for every protected API call.
- Capability flags map session authorities only for frontend UX availability and request suppression.
- Capability flags must never be treated as enforcement.
- Production runtime should use `WorkspaceRuntimeProvider` as the explicit API-client/session boundary.
- Tests, stories, and standalone hook verification may pass explicit `apiClient` overrides.
- Auth-sensitive UI code must not use default API wrappers, raw fetch, or module-global API session state.

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
- Capability state is tri-state: `true` means allowed, `false` means denied, and `undefined` means unknown/not ready.
- Unknown capabilities must not start auth-sensitive workspace reads.
- Governance advisory read views are currently backed by `transaction-monitor:read`.
- Governance audit writes are separate and require `governance-advisory:audit:write`.
- Frontend capabilities are UI/request gating only; backend authorization remains the source of truth.
- Workspace hooks abort in-flight requests and ignore stale responses on session/client/workspace switches.
- Alert and fraud-case detail reads abort in-flight requests and ignore stale responses on `alertId`/`caseId` or API-client switches.
- Alert assistant summaries are bound to the loaded alert request context; stale summaries from an older alert or API client are discarded.
- Alert decision submits and fraud-case updates pass `AbortSignal` through the API client while preserving idempotency keys.
- Alert decision refresh callbacks run only after the current alert detail refresh completes with a loaded result.
- Fraud-case update submit state is cleared on `caseId` or API-client switches so old mutation state does not bleed into the active detail view.
- Workspace counters clear on logout and session boundary reset.
- Partial counter failures set `degraded`; retained previous values are marked `stale` and shown as last-known values.
- Missing authority makes a counter unavailable, not zero.
- Governance audit workflow returns controlled `{ ok: false, reason, message }` results for expected local guard or API failures so the UI can show bounded errors without unhandled promise rejections.

## Authority Mapping

| Frontend capability | Backend authority currently mapped |
| --- | --- |
| Alerts workspace read | `alert:read` |
| Fraud case workspace read | `fraud-case:read` |
| Transaction/scoring workspace read | `transaction-monitor:read` |
| Governance advisory read | `transaction-monitor:read` |
| Governance audit write | `governance-advisory:audit:write` |

Governance advisory read intentionally follows the current backend authorization model. Do not introduce a frontend-only governance-read concept without a backend contract change.

## Governance Capability Mapping

- `canReadGovernanceAdvisories` is currently backed by `transaction-monitor:read`.
- `canWriteGovernanceAudit` is backed by `governance-advisory:audit:write`.
- Backend authorization remains authoritative.
- Do not use `canReadGovernanceAdvisories` for write affordances.

## Workspace Composition

FDP-51 creates a workspace runtime layer. `WorkspaceDashboardShell` composes the runtime hooks, `WorkspaceDetailRouter` owns detail-page routing, and `AlertsListPage` renders the current workspace surfaces.
Additional workflow logic must stay in workspace-specific hooks or containers instead of accumulating in the shell.

## UI Session Boundary

`workspaceSessionResetKey` and the `App.jsx` detail-selection reset key are UI consistency boundaries. They clear counters and stale details across user, role, authority, or provider-kind changes.
They are not API credential freshness keys. API client freshness comes from recreating `createAlertsApiClient({ session, authProvider })` from the full session and auth provider objects.

## Counter Semantics

- Counters are workspace UX metadata, not globally consistent truth.
- `null` means unavailable, not authorized, or not loaded; it does not mean zero.
- `stale` means a previous value is retained after a failed refresh.
- `lastRefreshedAt` is displayed as the last successful refresh, not current global freshness.

## Scope Guard

`check-fdp51-scope.mjs` is a CI guardrail, not a replacement for manual review. It blocks backend production changes, endpoint strings outside the API client boundary, raw fetch patterns covered by the API boundary gates, and export/bulk/assignment/mass-action keywords.
The guard is regex-based and intentionally conservative.

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
