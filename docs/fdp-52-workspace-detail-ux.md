# FDP-52 Workspace Detail UX Pattern

FDP-52 detail and mutation components are frontend runtime consumers only. Backend authorization remains authoritative, and UI capability checks are presentation guards.

FDP-52 introduces workspace-specific presentation boundaries. It moves rendering boundaries out of `AlertsListPage` and improves detail UX readiness. `WorkspaceDashboardShell` remains the runtime composition hub. Workspace containers are not security boundaries, and backend authorization remains authoritative. Frontend capability checks are UX/runtime gating only.

FDP-52 does not add backend endpoints, new mutation workflows, assignment, claim, export, bulk actions, idempotency semantics, Kafka/outbox/finality, or new auth modes. Future work may move hook ownership into workspace-specific runtime containers after lifecycle and duplicate-fetch risks are covered by tests.

## Detail Reads

Every new detail read component must use:

- `AbortController` when the API method accepts a `signal`.
- A request sequence guard so older responses cannot overwrite newer state.
- A context guard for the entity ID and explicit `apiClient`.
- Cleanup on unmount and on entity/client changes.

Failed refreshes that leave previous detail data visible must label that data as stale. Stale detail stays read-only unless a separate review approves the action path.

## Mutation UI

Every new mutation UI must use:

- The explicit runtime-provided `apiClient`.
- An idempotency key when the backend endpoint requires one.
- Web Crypto only for idempotency key generation.
- A context guard before `setState` and before `onUpdated` callbacks.
- Clear separation between mutation success and a later dashboard/detail refresh failure.

`Math.random` fallback is intentionally forbidden. If secure randomness is unavailable, mutation UI must fail closed before sending the request. This protects request identity quality but does not make mutation abort a backend rollback.

Mutation abort is a frontend request lifecycle guard, not a backend rollback guarantee. If an unsafe request reached the backend, it may complete; UI must rely on idempotency keys, the server response, and post-save refresh.

Do not update business state speculatively in the UI without a separate architecture review.
