# FDP-27 Merge Gate

## Required Before Merge

- Bank/prod/staging startup fails closed unless transaction mode is `REQUIRED`.
- Bank/prod/staging startup fails closed unless FDP-24 external anchoring publication is enabled, required, and fail-closed.
- Bank/prod/staging rejects `disabled`, `noop`, `local-file`, `in-memory`, and `same-database` external anchor sinks.
- Bank/prod/staging requires Trust Authority signing for external evidence.
- Bank/prod/staging requires a `JwtDecoder`, `app.security.jwt.required=true`, and no demo/header auth filter.
- Transaction capability probe is enabled and passes.
- Trust incident refresh mode is `ATOMIC`; `PARTIAL` is local/dev only.
- Outbox publisher, recovery, and dual-control confirmation are enabled.
- Sensitive operational reads use `SensitiveReadAuditService`.
- `@AuditedSensitiveRead` is marker-only; controllers must still call `SensitiveReadAuditService`.
- Sensitive read audit is fail-closed in bank/prod.
- Operational reads are bounded and do not expose raw payloads, raw idempotency keys, tokens, stack traces, or full URLs.
- Trust level reports bank profile posture as `BANK_PROFILE_ACTIVE` or `NON_BANK_LOCAL_MODE`.
- Architecture tests cover write-path boundaries and sensitive-read audit boundaries.
- Runbooks, SLOs, config matrix, and source-of-truth docs are present.

## Explicit Non-Goals

FDP-27 does not provide distributed ACID, does not provide exactly-once Kafka delivery, does not provide WORM storage, does not provide legal notarization, does not create a regulator-certified archive, and does not guarantee no mutation before evidence through a new pre-commit/finalize protocol.

Those remain future scope where explicitly required.
