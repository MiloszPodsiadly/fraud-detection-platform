# Policy publication recovery

Publication is performed only by the isolated publisher worker. The alert API can inspect and queue a bounded recovery; it does not possess the issuer private key or sign in an HTTP request.

| Durable state | Worker/operator behavior |
| --- | --- |
| `PENDING` | Worker atomically claims the original request. |
| `CLAIMED` | Live lease excludes other workers; an expired lease can be reclaimed. A crash leaves its attempt outcome explicitly uncertain. |
| `RETRYABLE` | Worker retries a transient operational failure within the automatic attempt budget. |
| `BLOCKED` | No worker claim. `ATTEMPTS_EXHAUSTED` alone permits a separately authorized recovery request, at most three times. `IMMUTABLE_CONFLICT`, `CURRENT_TRUST_OR_EVIDENCE_INVALID`, and `RECOVERY_BUDGET_EXHAUSTED` require investigation, not a state reset. |
| `PUBLISHED` | Original job, immutable revision, signed policy and approval evidence remain linked. |

`GET /api/v1/governance/model-policies/{id}/publication/recovery` returns the original publication request ID, frozen draft SHA-256, state, attempt count, safe failure code, lease expiry, committed-evidence indication, retry eligibility and durable attempt/recovery history. `POST` at the same path accepts `{ "operationId": "unique-operator-command-id" }`. The same operation ID by the same actor is idempotent. A recovery request changes only `BLOCKED` to `RETRYABLE` and increments a bounded recovery counter in one Mongo transaction with its append-only operator event. It never changes the draft, approval, revision, or original request ID.

Both endpoints require `model-policy:publication:recover`. A recovery operator must be distinct from the author, approver and original publication requester. Production additionally requires `model-policy:scope:production` and `model-policy:approve`; the backend enforces these independently of UI visibility. Local Keycloak fixture `policy-recovery` is for development only. Existing persisted realms require deliberate group/user provisioning.

Before signing, the worker rechecks the exact proposal, WebAuthn approval evidence, current actor/publisher/issuer mandates and issuer signing authority. If equivalent publication evidence already committed after a lost acknowledgement, the worker reconciles it without a new signature. Conflicting immutable material is never overwritten. A failed primary read after an unknown commit is not treated as proof that no publication happened; the lease is left for later reconciliation. A blocked job is never represented as published merely because a recovery request was accepted.

Operator recovery is deliberately unavailable for permanent conflict or invalid current trust. Restore trusted configuration or resolve the incident through a separately reviewed new policy revision; do not edit Mongo job status or reset attempt counters by hand. The attempt ledger has no TTL and should be retained with the policy evidence under the audit retention schedule.
