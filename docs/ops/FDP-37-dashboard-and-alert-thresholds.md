# FDP-37 Dashboard And Alert Thresholds

FDP-37 release review requires dashboards and alerts for regulated mutation restart safety. Labels must stay low-cardinality: `model_version`, `state`, `outcome`, `reason`, `checkpoint`, and `threshold` are allowed. Do not use command id, alert id, actor id, idempotency key, lease owner, raw exception, request path, or token labels.

| Alert | Metric | Threshold | Severity | Owner | Runbook | Safe operator action | Forbidden action |
| --- | --- | --- | --- | --- | --- | --- | --- |
| stale owner spike | `regulated_mutation_stale_write_rejected_total` | `increase(...[5m]) >= 5` | warning | fraud-platform-oncall | FDP-37 recovery drill | inspect recovery queue and lease budget | manually edit lease owner |
| expired lease spike | `regulated_mutation_stale_write_rejected_total{reason="EXPIRED_LEASE"}` | `increase(...[5m]) >= 3` | warning | fraud-platform-oncall | FDP-37 recovery drill | run bounded recovery endpoint | replay with new idempotency key |
| lease budget exceeded | `regulated_mutation_checkpoint_renewal_blocked_total{reason="BUDGET_EXCEEDED"}` | `increase(...[10m]) >= 1` | critical | regulated-mutation-owner | FDP-34 checkpoint runbook | pause enablement review and inspect checkpoint budget | increase lease budget in production without review |
| long-running processing age | `regulated_mutation_recovery_processing_oldest_age_seconds` | `> 120s for 5m` | warning | fraud-platform-oncall | FDP-37 recovery drill | inspect in-progress commands | mark completed manually |
| repeated takeover | `regulated_mutation_lease_takeover_total` | `increase(...[10m]) >= 3` | warning | regulated-mutation-owner | FDP-32 lease fencing runbook | inspect worker health and lease duration | disable fencing |
| finalize recovery required | `regulated_mutation_recovery_required_total` | `> 0 for 5m` | critical | regulated-mutation-owner | FDP-29 recovery runbook | keep pending evidence state; do not force external finality | force evidence confirmed |
| checkpoint renewal failed | `regulated_mutation_checkpoint_renewal_total{outcome="FAILED"}` | `increase(...[10m]) >= 1` | warning | regulated-mutation-owner | FDP-34 checkpoint runbook | check lease renewal policy and Mongo health | bypass checkpoint renewal |
| production image chaos job failed | GitHub Actions `fdp37-production-image-chaos` | any failed required run | critical | release-owner | FDP-37 merge gate | block merge and review artifact | merge with missing proof summary |
| duplicate outbox invariant failed | FDP-37 proof artifact invariant | any duplicate outbox row | critical | release-owner | FDP-37 proof pack | stop enablement review and investigate | delete duplicate rows manually |
| duplicate SUCCESS audit invariant failed | FDP-37 proof artifact invariant | any duplicate SUCCESS audit | critical | release-owner | FDP-37 proof pack | stop enablement review and investigate | edit audit chain |

Dashboard panels should show stale rejections, lease takeovers, recovery-required count, processing age, checkpoint renewal outcomes, transition latency, and FDP-37 CI proof status.
