# FDP-50 Legacy Fraud-Case API Removal

## Historical Behavior In FDP-50

FDP-50 was a breaking API change for fraud-case clients. At that release boundary, authenticated requests to
`/api/fraud-cases/**` returned `410 Gone` with `code:LEGACY_FRAUD_CASE_ROUTE_REMOVED`; unauthenticated requests
failed at the authentication boundary. Clients were required to move to versioned routes.

## Current Behavior After FDP-81

FDP-81 removes the unversioned compatibility handler. Clients must not rely on `410 Gone` or
`LEGACY_FRAUD_CASE_ROUTE_REMOVED` after FDP-81. Requests to removed unversioned routes now follow the normal
unknown-route and security fallback behavior.

FDP-81 also removes unused versioned list, standalone lifecycle, and fraud-case audit-trail handlers. Supported
clients must use only the retained versioned surface documented in [Fraud Case API](../api/fraud_case_api.md).

The FDP-50 frontend boundary remains applicable: auth-sensitive Analyst Console code uses
`createAlertsApiClient({ session, authProvider })`, and removed default compatibility wrappers from `alertsApi.js`
must not be reintroduced.
