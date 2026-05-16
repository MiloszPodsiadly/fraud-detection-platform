# FDP-53 Workspace Runtime Ownership

Status: branch evidence.


## Goal

FDP-53 moves workspace-specific runtime ownership out of `WorkspaceDashboardShell` so the shell remains a composition layer instead of becoming the next central application component.

## Architecture

`WorkspaceDashboardShell` owns only the runtime provider boundary usage, active workspace selection, `WorkspaceRouteRegistry` lookup, shared header/counters, `WorkspaceDetailRouter`, the active workspace outlet, and top-level session blocking state.

`WorkspaceRouteRegistry` is declarative metadata: workspace key, labels, route value, heading label, capability key reference, and Runtime component. `WORKSPACE_ROUTE_ORDER` is the explicit UX order for navigation. `capabilityKey` is display/runtime metadata resolved by `WorkspaceRuntimeProvider`; it is not policy. The registry does not compute authorities, create API clients, call APIs, or encode business workflow rules.

`WorkspaceRuntimeProvider` remains the source of normalized frontend runtime context. It exposes the current session, auth provider, API client, capability booleans, and runtime status to mounted workspace runtime layers. It is not a security boundary; backend authorization remains authoritative.

Workspace-specific runtime layers own their workspace reads and local callbacks:

- `AnalystWorkspaceRuntime`: fraud-case work queue and global fraud-case summary.
- `FraudTransactionWorkspaceRuntime`: alert queue and alert pagination.
- `TransactionScoringWorkspaceRuntime`: scored transaction filters and pagination.
- `GovernanceWorkspaceRuntime`: governance advisory queue and guarded audit workflow.
- `ReportsWorkspaceRuntime`: governance analytics read model.

Only the active workspace runtime is mounted. Hidden workspace runtimes do not fetch domain data. Shared counters remain shell-owned global dashboard signals and may fetch minimal count reads when `sharedWorkspaceReadsEnabled`.

Workspace refresh has one contract: `createWorkspaceRefreshHandler` calls the active runtime `refreshWorkspace()` and then refreshes shared counters when shared reads are enabled. The contract returns low-cardinality results for blocked sessions, successful dispatch, and synchronous refresh-start failures. `WorkspaceDashboardShell` consumes those results through a small refresh-notice hook so skipped or failed refresh attempts are visible without logging entity identifiers.

Compliance audit refreshes the compliance advisory queue only. Reports analytics is owned by `ReportsWorkspaceRuntime`, shows the last loaded analytics snapshot, and refreshes when Reports is active or explicitly retried.

## Adding A Workspace

Add a `WorkspaceRouteRegistry` entry with key, labels, route value, heading, capability key reference, and Runtime component.

Add a workspace runtime layer that consumes `WorkspaceRuntimeProvider` context and owns only that workspace's read hooks and local callbacks.

Add a container/page pair for presentation. Containers remain presentation boundaries and must not import API clients or call raw `fetch`.

Add no-duplicate-fetch tests proving only the active runtime fetches. Add session switch and authority-loss tests proving old data clears or cannot be committed. Add focus/detail tests when the workspace has a detail route.

## Hard Rules

- No raw `fetch` outside API/auth layers.
- No default API wrapper imports in workspace runtime/container code.
- No API client creation inside workspace runtime.
- No speculative prefetching.
- No hidden workspace sensitive reads.
- No backend production changes in FDP-53.
- No fake empty fallback states for not-mounted, unavailable, unauthorized, loading, or failed runtime data.

## Auth Model

The registry may reference capability keys such as `canReadFraudCases`; it must not calculate roles, authorities, or token state. Frontend capability flags only shape UI availability. Backend authorization remains the source of truth for every protected read and mutation.

## Non-Goals

FDP-53 does not add assignment, claim, export, bulk, or mass-action workflows. It does not add new mutations, idempotency semantics, Kafka/outbox/finality behavior, backend endpoints, or auth modes.

## Failure Modes

Duplicate fetches can occur if a workspace hook remains in the shell or if multiple runtimes are mounted at once.

Stale state can appear if session or authority switches do not clear old runtime data.

The registry becomes dangerous if it starts computing authorization instead of referencing capability keys.

The shell becomes hard to reason about if workspace-specific hooks, filters, pagination, or retry behavior move back into it.

Invalid workspace route inputs are normalized to the Fraud Case workspace through an explicit fallback notice. That fallback is UX routing behavior only and does not grant authority or bypass backend authorization.
