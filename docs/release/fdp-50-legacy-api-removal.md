# FDP-50 Legacy Fraud-Case API Removal

FDP-50 is a breaking API change for fraud-case clients.

- `/api/fraud-cases/**` is removed. Clients must use `/api/v1/fraud-cases/**`.
- Authenticated retired legacy fraud-case routes return `410 Gone` with `code:LEGACY_FRAUD_CASE_ROUTE_REMOVED`; unauthenticated requests still fail at the authentication boundary.
- Auth-sensitive Analyst Console code must use `createAlertsApiClient({ session, authProvider })`.
- Default compatibility wrappers from `alertsApi.js` were removed and must not be reintroduced.
- Backend security, read-audit classification, metrics, tests, docs, and the FDP-50 scope guard are aligned to the versioned route family.

FDP-50 does not change fraud-case lifecycle behavior, idempotency behavior, Kafka/outbox/finality handling, external audit anchoring, export workflows, bulk actions, or assignment product workflows.
