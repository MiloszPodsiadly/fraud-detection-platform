# ML Prediction Evidence Recovery

## Scope

This runbook recovers the private `MlPredictionEvidence` projection only. It never republishes a failed
record to `transactions.scored`, because that topic also activates baseline alert, fraud-case, and audit processing.

The recovery topics are:

- `ml.prediction-evidence.dead-letter`: valid records that exhausted bounded retries after a transient failure;
- `ml.prediction-evidence.redrive`: controlled input for evidence-only recovery;
- `ml.prediction-evidence.quarantine`: malformed, permanently invalid, or conflicting records requiring review.

The redrive listener is disabled by default. Enable it only for an approved recovery window with
`ML_PREDICTION_EVIDENCE_REDRIVE_ENABLED=true` and a dedicated service identity that can read the redrive topic and
write the private evidence collection.

## Recovery Procedure

1. Confirm the evidence MongoDB store is healthy and the projection-failure rate has returned to normal.
2. Record the DLT topic partitions and offsets selected for recovery. Do not select terminal quarantine records.
3. With approved Kafka record-copy tooling, copy each selected DLT record byte-for-byte to
   `ml.prediction-evidence.redrive`. Preserve the key, value, and all headers, including the original topic,
   partition, and offset headers created by Spring Kafka.
4. Start one alert-service recovery instance with redrive enabled. Keep the normal evidence consumer running; the
   redrive group is independent and invokes only the canonical evidence projection boundary.
5. Monitor `ml_prediction_evidence_recovery_total`, evidence projection metrics, redrive consumer lag, DLT growth,
   and quarantine growth. Stop the recovery instance if transient failures resume.
6. Confirm the selected redrive offsets are committed and each source event ID has exactly one immutable evidence
   document. Repeating the same approved copy is safe and is classified as an idempotent replay.
7. Disable redrive after the selected backlog is drained. Retain the original DLT records until the recovery evidence
   and operational approval record have been reviewed.

Records without original source coordinates are rejected. A transient redrive failure returns to the evidence DLT
after bounded retries; a permanent failure moves to quarantine. Neither destination is consumed automatically, so a
failed or invalid record cannot enter an automatic re-drive loop.

## Access And Retention

The three evidence recovery topics contain the original scored-event bytes and therefore require the same private
access controls as `transactions.scored` and the evidence collection. The normal alert consumer must not read the
redrive topic. Recovery operators require time-bounded read access to the evidence DLT and write access to redrive;
quarantine access is limited to approved governance and incident-response roles.

Source-topic retention must cover normal consumer outage recovery. Evidence DLT and quarantine retention must be
longer than the maximum operational investigation and redrive window. Governance evidence retention may be longer
still; Kafka retention is not a substitute for the governed immutable MongoDB evidence record or its approved archive.
No DLT or quarantine record may be deleted until the associated recovery or terminal disposition is documented.
