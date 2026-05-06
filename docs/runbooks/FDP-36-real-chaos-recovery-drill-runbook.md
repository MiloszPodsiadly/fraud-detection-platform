# FDP-36 Real Chaos Recovery Drill Runbook

This runbook records test/dev real chaos drill output. It is sample operational evidence only, not production evidence and not production enablement.

FDP-36 kills and restarts the real alert-service JVM/process. Most crash windows are durable-state crash-window proofs, not live in-flight instruction-boundary kills.

Docker/Testcontainers are infrastructure dependencies, not the killed alert-service image.

## Drill Fields

- drill owner
- approver
- test environment
- killed process
- restarted process
- proof level
- Mongo/Testcontainers instance
- crash window selected
- kill timestamp
- restart timestamp
- command id hash
- idempotency key hash
- pre-kill state
- post-restart state
- API response summary
- outbox count
- SUCCESS audit count
- business mutation count
- operator decision
- rollback needed? yes/no
- final status
- lessons learned

## Forbidden In Drill Output

- raw idempotency key
- raw payload
- raw token
- raw lease owner
- raw stack trace

## Sample Filled Output

This is sample test/dev output, not production evidence.

| Field | Sample |
| --- | --- |
| drill owner | platform-test |
| approver | platform-owner |
| test environment | local Testcontainers |
| killed process | actual alert-service JVM/process running `com.frauddetection.alert.AlertServiceApplication` |
| restarted process | actual alert-service JVM/process running `com.frauddetection.alert.AlertServiceApplication` |
| proof level | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| Mongo/Testcontainers instance | mongo:7.0 replica-set container |
| crash window selected | legacy BUSINESS_COMMITTING |
| kill timestamp | 2026-05-06T08:00:00Z |
| restart timestamp | 2026-05-06T08:00:03Z |
| command id hash | sha256:command-window-redacted |
| idempotency key hash | sha256:idempotency-redacted |
| pre-kill state | BUSINESS_COMMITTING / PROCESSING |
| post-restart state | BUSINESS_COMMITTING / RECOVERY_REQUIRED |
| API response summary | recovery-required, no committed success |
| outbox count | 0 |
| SUCCESS audit count | 0 |
| business mutation count | 0 |
| operator decision | inspect and recover, do not replay with a new key |
| rollback needed? | no |
| final status | PASS |
| lessons learned | recovery state remained explicit after real alert-service kill/restart |

Documentation must not call dummy-container proof real service chaos. If the killed target is not alert-service, classify the result as `DUMMY_CONTAINER_DURABLE_STATE_PROOF` or `MODELED_DURABLE_STATE_PROOF`, not FDP-36 GO evidence.

Full alert-service image container chaos is future scope: build the production image, start that image under Docker Compose/Testcontainers, kill the alert-service image container, restart the same image, and verify API/recovery/outbox/audit through the restarted image. FDP-36 does not claim production Docker image chaos or full container image kill proof.
