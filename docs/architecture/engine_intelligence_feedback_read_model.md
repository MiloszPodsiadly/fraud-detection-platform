# Engine Intelligence Feedback Read Model

## Purpose

FDP-99 exposes captured feedback through a bounded, authorized, transaction-scoped read model. Feedback can be reviewed, not executed.

## Scope

FDP-99 is governance/review only. It adds `GET /api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback` for reading captured engine intelligence feedback for one scored transaction.

## Non-goals

FDP-99 does not add analytics dashboards, global search, case aggregation, training export, model retraining, rule updates, approve/decline/block, alert severity changes, or fraud case status changes.

## Endpoint

The endpoint is transaction-scoped only: `GET /api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback`. It validates `transactionId` with the existing bounded engine intelligence transaction id policy and returns 404 when the scored transaction is missing.

## DTO Boundary

The read contract uses `EngineIntelligenceFeedbackReadModel`, `EngineIntelligenceFeedbackEntryReadModel`, and `EngineIntelligenceFeedbackPage`. It does not return the Mongo persistence document or reuse the submit response.

## Authorization

Feedback reads require `ENGINE_INTELLIGENCE_FEEDBACK_READ`. `TRANSACTION_MONITOR_READ` can read the engine intelligence display, and `ENGINE_INTELLIGENCE_FEEDBACK_WRITE` can submit feedback, but neither authority alone reads captured feedback.

## Bounded First Page

Reads are bounded first-page reads. FDP-99 returns the first bounded page of latest feedback. The default limit is 25, the maximum limit is 50, and the service requests one extra row internally to compute `page.hasMore`. hasMore indicates additional feedback exists, not navigation state. Cursor-based continuation is future scope. No unbounded findAll/read-all endpoint is allowed.

## Missing Feedback Behavior

An existing scored transaction without feedback returns 200 with `feedback: []` and `page.hasMore: false`. A missing scored transaction returns 404.

## submittedBy Privacy

submittedBy is omitted by default in FDP-99 v1. Any future submittedBy exposure requires stronger explicit permission and separate review.

## Read Audit Policy

FDP-99 follows existing read audit policy for sensitive analyst feedback. In fail-closed audit modes, read audit failure returns a bounded 503. Feedback read audit is bounded and does not include raw/internal data.

## No Raw/Internal Leakage

The read model omits actor identity, idempotency hashes, request payload hashes, correlation identifiers, persistence timestamps, audit internals, raw request bodies, raw engine intelligence payloads, Mongo metadata, and internal class names.

## No Decisioning/Retraining/Rule Updates

Feedback is analyst perception/review input, not ground truth, training label, model correction, scoring override, or final decision. The read endpoint does not change alert severity, fraud case status, scoring state, model state, rules, payment authorization, outbox commands, or downstream decisions.

## Future Scopes

Any future analytics, export, case aggregation, actor disclosure, training integration, or decisioning workflow must be designed and reviewed as a separate task with its own authorization, privacy, audit, and contract boundaries.
