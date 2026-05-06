# FDP-37 Dashboard And Alert Thresholds

FDP-37 release review requires dashboards and alerts for regulated mutation restart safety. Labels must stay low-cardinality: `model_version`, `state`, `outcome`, `reason`, `checkpoint`, and `threshold` are allowed. Do not use command id, alert id, actor id, idempotency key, lease owner, raw exception, request path, or token labels.

## Runtime Thresholds

| Signal | Metric | Threshold | Severity | Owner | Runbook | Safe action | Forbidden action |
| --- | --- | --- | --- | --- | --- | --- | --- |
| stale owner spike | `regulated_mutation_stale_write_rejected_total` | `increase(...[5m]) >= 5` | warning | fraud-platform-oncall | FDP-37 recovery drill | inspect recovery queue and lease budget | manually edit lease owner |
| expired lease spike | `regulated_mutation_stale_write_rejected_total{reason="EXPIRED_LEASE"}` | `increase(...[5m]) >= 3` | warning | fraud-platform-oncall | FDP-37 recovery drill | run bounded recovery endpoint | replay with new idempotency key |
| recovery conflict | `regulated_mutation_recovery_write_conflict_total` | `increase(...[5m]) > 0` | critical | fraud-platform-oncall | FDP-37 recovery drill | inspect command and preserve fencing | force command completion |
| long-running processing age | `regulated_mutation_recovery_processing_oldest_age_seconds` | `> 120s for 5m` | warning | fraud-platform-oncall | FDP-37 recovery drill | inspect in-progress commands | mark completed manually |
| checkpoint renewal blocked | `regulated_mutation_checkpoint_renewal_blocked_total` | `increase(...[10m]) >= 3` | warning | fraud-platform-oncall | checkpoint renewal runbook | review checkpoint budget | disable fencing |
| low lease budget | `regulated_mutation_lease_budget_warning_total` | `increase(...[5m]) >= 3` | warning | fraud-platform-oncall | lease budget review | tune lease budget through release/config PR | change runtime state by hand |

## Release-Gate Thresholds

| Signal | Source | Threshold | Severity | Owner | Safe action | Forbidden action |
| --- | --- | --- | --- | --- | --- | --- |
| production image chaos job failed | GitHub Actions `fdp37-production-image-chaos` | any failed required run | critical | release-owner | block merge and review artifact | merge with missing proof summary |
| required FDP-37 test skipped | Surefire XML | any required skipped test | critical | release-owner | block merge | count skipped test as proof |
| missing image provenance | proof summary | missing SHA, tag, digest/id, or killed container id | critical | release-owner | rebuild proof image from current commit | use stale local image as merge evidence |
| duplicate outbox invariant failed | FDP-37 proof artifact invariant | any duplicate outbox row | critical | release-owner | stop enablement review and investigate | delete duplicate rows manually |
| duplicate SUCCESS audit invariant failed | FDP-37 proof artifact invariant | any duplicate SUCCESS audit | critical | release-owner | stop enablement review and investigate | edit audit chain |

Dashboard panels should show stale rejections, lease takeovers, recovery-required count, processing age, checkpoint renewal outcomes, transition latency, FDP-37 CI proof status, image provenance status, and rollback validation status.

FDP-37 uses a shared Testcontainers network with stable dependency aliases. This dashboard/runbook does not certify production Docker Compose networking or production networking.
