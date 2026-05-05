# FDP-35 E2E And Modeled Chaos Test Plan

FDP-35 proof tests validate existing regulated mutation behavior under production-readiness scenarios. They use Docker/Testcontainers where required and modeled durable states for restart/recovery proof.

## Test Tags

- `production-readiness`
- `e2e`
- `recovery-proof`
- `integration`

## Commands

Run only the FDP-35 proof suite:

```bash
mvn -B -pl alert-service -am -Dgroups=production-readiness,e2e,recovery-proof,integration test
```

Run the full regulated mutation regression suite:

```bash
mvn -B -pl alert-service -am -Dgroups=failure-injection,invariant-proof,integration,production-readiness,e2e,recovery-proof test
```

Run full alert-service tests:

```bash
mvn -B -pl alert-service -am test
```

## Required Scenarios

- Legacy submit-decision happy path E2E.
- Evidence-gated submit-decision happy path with flags enabled only in test context.
- Recovery state beats stale snapshot.
- Checkpoint renewal is not progress.
- Long-running PROCESSING is observable.
- Modeled crash after claim before attempted audit.
- Modeled crash after attempted audit before business mutation.
- Modeled crash after business commit before success audit.
- Modeled crash during FDP-29 finalize before commit.
- Modeled crash after FDP-29 local commit before external confirmation.
- Modeled crash with recovery state and stale snapshot.

## Expected Output Placeholder

```text
Tests run: <n>, Failures: 0, Errors: 0, Skipped: <n>
BUILD SUCCESS
```

## Limitations

These tests do not use real kill -9. They are modeled restart/recovery proof based on durable Mongo states that a process death could leave behind.

