# Transactional outbox recovery

## State transitions

```text
PROCESSING with expired fenced lease
  -> FAILED_RETRYABLE
  -> FAILED_TERMINAL when the retry budget is exhausted

PUBLISH_ATTEMPTED with expired fenced lease
  -> PUBLISH_CONFIRMATION_UNKNOWN
  -> explicit regulated confirmation resolution

PUBLISHED / PUBLISH_CONFIRMATION_UNKNOWN / FAILED_* / RECOVERY_REQUIRED
  -> projection repair claim
  -> alert projection conditional update
  -> clear projection mismatch under the same status, revision and repair token
```

`OutboxStaleLeaseRecovery` owns expired processing leases, retry exhaustion and uncertain publish attempts.
`OutboxProjectionReconciler` owns fair, bounded projection repair. `OutboxRecoveryService` coordinates these
mechanisms, pending publication and regulated confirmation resolution; it does not implement their Mongo state
transitions.

Every stale-lease update is fenced by event identity, expected status, lease owner, claim token, lease expiry and
attempt count. Projection repair is fenced by event identity, status, projection revision and repair token. A broker
send attempt never implies confirmation, and an alert projection update never authorizes clearing mismatch state
unless the authoritative outbox record still satisfies the completion fence.
