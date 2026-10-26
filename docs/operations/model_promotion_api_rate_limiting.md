# Model-promotion API rate limiting

The model-promotion governance API uses a one-minute per-actor quota. `429` means the shared or local quota was
exhausted. `503` means the limiter could not safely make a decision, including Redis errors, unsupported Redis
Cluster `CROSSSLOT` responses, or local actor-capacity exhaustion. The application never switches to a weaker limiter
after a Redis failure.

## Configuration acceptance

| Active profiles and bank flag | `MODEL_PROMOTION_API_RATE_LIMITER_MODE` | Startup/result |
| --- | --- | --- |
| Exactly named single-node profiles from `local`, `dev`, `test`, `docker-local`, `bank-local` | `in-memory` or `redis` | In-memory allowed only when every active profile is in this list; quota state is process-local and resets on restart. |
| No active profile, `default`, plain `docker`, or an unlisted profile | `redis` | `in-memory` is rejected; select an explicit single-node profile for local use. |
| `prod`, `production`, `staging`, `bank`, or any set containing one of them, including `test,prod` | `redis` | Required; quotas are shared between replicas. `in-memory` is rejected at startup. |
| Any profile with `app.audit.bank-mode.fail-closed=true` | `redis` | Required regardless of profile spelling, including `default` and `bank-local`; `in-memory` is rejected at startup. |
| any profile | blank or unknown value | Rejected at startup. |

For multi-instance deployments, point every `alert-service` instance at the same managed **standalone Redis** service
and set `MODEL_PROMOTION_API_RATE_LIMITER_MODE=redis`. Verify the configured `REDIS_HOST` and `REDIS_PORT` for each
instance, then perform a controlled request sequence through two instances and confirm the shared quota is enforced.
Redis Cluster is not supported: the current atomic Lua script uses keys without a shared hash tag, so Cluster may
return `CROSSSLOT`. Do not route production governance traffic through that topology or treat `CROSSSLOT` as quota
exhaustion; it fails closed as `503`.

The committed [`deployment/.env.example`](../../deployment/.env.example) selects Redis for the local Compose stack.
Its `alert-service` uses the plain `docker` profile, which does not authorize in-memory quotas. Change to an explicit
allowlisted single-node profile before selecting in-memory, and keep `app.audit.bank-mode.fail-closed=false`. The
application configuration defaults to Redis and does not provide an in-memory fallback.
