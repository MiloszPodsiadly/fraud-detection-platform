# FDP-36 Enablement Decision Checklist

FDP-36 produces a READY / NOT READY decision pack for future controlled enablement. It does not enable production automatically.

READY_FOR_ENABLEMENT_REVIEW is not production enablement.

## Checklist

| Area | Required Evidence | Result |
| --- | --- | --- |
| Real chaos job result | `fdp36-real-chaos` green with no Docker skip | TBD |
| Docker/Testcontainers output link | CI artifact and job log linked | TBD |
| Crash windows covered | Proof matrix rows identify covered and uncovered windows | TBD |
| Gaps / unsupported windows | Any `NOT_COVERED_IN_FDP36` row has reason and future scope | TBD |
| Recovery API proof result | Post-restart API tests green | TBD |
| Outbox/audit duplicate proof result | Evidence integrity tests green | TBD |
| Long-running/expired processing observability | API proof shows observable non-success states | TBD |
| Operator runbook drill result | Drill output completed without raw sensitive values | TBD |
| Rollback plan validation | Rollback checks confirm feature flags remain default-off | TBD |
| Feature flags remain default-off | FDP-29 production mode is not auto-enabled | TBD |
| Required approvers | Platform owner, security owner, operations owner | TBD |

## Final Decision

Choose one:

- READY_FOR_ENABLEMENT_REVIEW
- NOT_READY
- READY_WITH_LIMITATIONS

## Guardrail

This checklist must not say production enabled, auto-enabled, deploy enabled, or bank enabled unless the sentence explicitly says the result is only a future review decision.

