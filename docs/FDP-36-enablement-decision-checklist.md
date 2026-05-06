# FDP-36 Enablement Decision Checklist

FDP-36 produces a READY / NOT READY decision pack for future controlled enablement. It does not enable production automatically.

READY_FOR_ENABLEMENT_REVIEW is not production enablement.

## Checklist

| Area | Required Evidence | Allowed Values | Result |
| --- | --- | --- | --- |
| CI run id/link | CI run URL or release-owner run URL | `https://...`, `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Real chaos job result | `fdp36-real-chaos` green with no Docker skip | `PASS`, `FAIL`, `NOT_RUN` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Killed process evidence | job output names actual `alert-service` JVM/process | `alert-service`, `NOT_READY` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Regulated mutation regression result | `regulated-mutation-regression` green | `PASS`, `FAIL`, `NOT_RUN` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Crash windows covered | proof matrix rows identify covered and unsupported windows | comma-separated window names | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Proof levels covered | proof levels from artifact summary | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF`, `API_PERSISTED_STATE_PROOF`, `MODELED_DURABLE_STATE_PROOF` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Unsupported windows | explicit future scope list | list, or `NONE_DECLARED` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Recovery API proof result | restarted `alert-service` recovery/inspection API proof green | `PASS`, `FAIL`, `NOT_RUN` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Duplicate evidence proof result | outbox/audit/local-anchor duplicate tests green | `PASS`, `FAIL`, `NOT_RUN` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Runbook drill result | drill output completed without raw sensitive values | `PASS`, `FAIL`, `NOT_RUN` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Rollback validation | rollback checks confirm feature flags remain default-off | `PASS`, `FAIL`, `NOT_RUN` | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |
| Required approvers | dual-control approval fields completed | platform owner, security owner, operations owner | `TO_BE_FILLED_BY_CI_OR_RELEASE_OWNER` |

## Final Decision

Choose one:

- NOT_READY
- READY_WITH_LIMITATIONS
- READY_FOR_ENABLEMENT_REVIEW

## Guardrail

This checklist must not say production enabled, auto-enabled, deploy enabled, or bank enabled unless the sentence explicitly says the result is only a future review decision.

## Evidence Mapping

| Invariant | Test class/method | CI job | Artifact expected | Proof level |
| --- | --- | --- | --- | --- |
| no false committed success after real service kill | `RegulatedMutationRealAlertServiceChaosIT.shouldNotReturnFalseSuccessAfterKillDuringLegacyBusinessCommitting` | `fdp36-real-chaos` | `target/fdp36-chaos/evidence-summary.md` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| pending external remains pending | `RegulatedMutationRealAlertServiceChaosIT.shouldRemainPendingExternalAfterKillWhenFdp29LocalCommitCompletedButExternalEvidencePending` | `fdp36-real-chaos` | `target/fdp36-chaos/evidence-summary.md` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| duplicate outbox blocked after restart | `RegulatedMutationRealAlertServiceEvidenceIntegrityIT.replayAfterRestartMustNotCreateSecondOutboxRecord` | `fdp36-real-chaos` | surefire report | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| persisted-state API safety | `RegulatedMutationPostRestartApiBehaviorTest.inspectionAfterRestartDoesNotExposeRawSensitiveFields` | `regulated-mutation-regression` | surefire report | `API_PERSISTED_STATE_PROOF` |

## Filled Test/Dev Sample

| Field | Sample Value |
| --- | --- |
| CI run id/link | `local-dev-run-2026-05-06` |
| Real chaos job result | `PASS` |
| Killed process evidence | `alert-service JVM/process` |
| Proof levels covered | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF`, `API_PERSISTED_STATE_PROOF` |
| Final decision | `READY_FOR_ENABLEMENT_REVIEW` |

The sample is not production evidence and not production enablement.
