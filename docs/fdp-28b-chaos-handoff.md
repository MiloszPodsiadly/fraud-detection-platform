# FDP-28B Chaos Testing Handoff

FDP-28 is an invariant and modeled failure-injection proof pack. FDP-28B is the future scope for real chaos testing that intentionally kills processes, disrupts brokers, and exercises restart behavior under controlled conditions.

## Future Scope

- Real process kill after local transaction commit before SUCCESS audit.
- Real process kill after broker publish accepted before local `markPublished`.
- Process restart and idempotency replay.
- Mongo primary failover during regulated mutation transaction.
- Kafka broker outage or timeout ambiguity with Testcontainers.
- External witness write succeeds but local status persistence fails.
- External witness unavailable under bank fail-closed mode.
- Full HTTP/e2e sensitive read audit fail-closed under unavailable audit persistence.

## Non-Goals

- No distributed ACID.
- No exactly-once Kafka.
- No legal notarization.
- New business features.

## Merge Boundary

FDP-28B should prove process and dependency failure behavior without changing scoring, model behavior, Kafka event contracts, analyst workflow semantics, or regulated mutation business outcomes.
