# Model-promotion API rate limiting

The model-promotion governance API uses a one-minute per-actor quota. `429` means the shared or local quota was
exhausted. `503` means the limiter could not safely make a decision, including Redis errors, unsupported Redis
Cluster `CROSSSLOT` responses, or local actor-capacity exhaustion. The application never switches to a weaker limiter
after a Redis failure.

## Configuration acceptance

| Active profile | `MODEL_PROMOTION_API_RATE_LIMITER_MODE` | Startup/result |
| --- | --- | --- |
| no production profile, single-node local | `in-memory` | Allowed; quota state is process-local and resets on restart. |
| `test` | `in-memory` or `redis` | Allowed for tests. |
| `bank-local` | `in-memory` or `redis` | Allowed for single-node smoke use only. |
| `prod`, `production`, `staging`, or `bank` | `redis` | Required; quotas are shared between replicas. |
| any combination containing a production profile, including `test,prod` | `in-memory` | Rejected at startup. |
| any profile | blank or unknown value | Rejected at startup. |

For multi-instance deployments, point every `alert-service` instance at the same managed **standalone Redis** service
and set `MODEL_PROMOTION_API_RATE_LIMITER_MODE=redis`. Verify the configured `REDIS_HOST` and `REDIS_PORT` for each
instance, then perform a controlled request sequence through two instances and confirm the shared quota is enforced.
Redis Cluster is not supported: the current atomic Lua script uses keys without a shared hash tag, so Cluster may
return `CROSSSLOT`. Do not route production governance traffic through that topology or treat `CROSSSLOT` as quota
exhaustion; it fails closed as `503`.

The committed [`deployment/.env.example`](../../deployment/.env.example) selects Redis for the local Compose stack.
The application configuration defaults to Redis and does not provide an in-memory fallback.
