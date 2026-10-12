package com.frauddetection.alert.outbox;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.recovery.OutboxProjectionRepairPolicy;
import com.frauddetection.alert.persistence.AlertDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class OutboxProjectionReconciler {

    private static final int RECONCILIATION_LIMIT = 100;
    private static final Duration REPAIR_LEASE = Duration.ofMinutes(1);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final List<TransactionalOutboxStatus> REPAIRABLE_STATUSES = List.of(
            TransactionalOutboxStatus.PUBLISHED,
            TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN,
            TransactionalOutboxStatus.FAILED_RETRYABLE,
            TransactionalOutboxStatus.FAILED_TERMINAL,
            TransactionalOutboxStatus.RECOVERY_REQUIRED
    );

    private final TransactionalOutboxRecordRepository repository;
    private final MongoTemplate mongoTemplate;
    private final AlertServiceMetrics metrics;

    OutboxProjectionReconciler(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics
    ) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
    }

    int reconcile() {
        int repaired = 0;
        Instant scanStartedAt = Instant.now();
        List<TransactionalOutboxRecordDocument> scheduled =
                repository.findTop100ByStatusInAndProjectionReconcileAfterLessThanEqualOrderByProjectionReconcileAfterAscCreatedAtAsc(
                        REPAIRABLE_STATUSES, scanStartedAt);
        List<TransactionalOutboxRecordDocument> unscheduled =
                repository.findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(
                        REPAIRABLE_STATUSES);
        List<TransactionalOutboxRecordDocument> candidates = OutboxProjectionRepairPolicy.fairCandidates(
                scheduled, unscheduled, RECONCILIATION_LIMIT);
        for (TransactionalOutboxRecordDocument record : candidates) {
            String repairToken = UUID.randomUUID().toString();
            Instant claimedAt = Instant.now();
            Query claim = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(record.getEventId()),
                    Criteria.where("status").is(record.getStatus()),
                    Criteria.where("projection_revision").is(record.getProjectionRevision()),
                    new Criteria().orOperator(
                            Criteria.where("projection_reconcile_after").lte(scanStartedAt),
                            new Criteria().andOperator(
                                    Criteria.where("projection_mismatch").is(true),
                                    Criteria.where("projection_reconcile_after").is(null)
                            )
                    ),
                    new Criteria().orOperator(
                            Criteria.where("projection_repair_token").exists(false),
                            Criteria.where("projection_repair_claimed_at").exists(false),
                            Criteria.where("projection_repair_claimed_at").lte(scanStartedAt.minus(REPAIR_LEASE))
                    )
            ));
            Update claimRepair = new Update()
                    .set("projection_repair_token", repairToken)
                    .set("projection_repair_claimed_at", claimedAt)
                    .set("projection_reconcile_after", scanStartedAt);
            if (mongoTemplate.updateFirst(claim, claimRepair, TransactionalOutboxRecordDocument.class)
                    .getModifiedCount() != 1) {
                continue;
            }
            AlertDocument alert = record.getResourceId() == null || record.getResourceId().isBlank()
                    ? null
                    : mongoTemplate.findById(record.getResourceId(), AlertDocument.class);
            String rejectionReason = OutboxProjectionRepairPolicy.rejectionReason(record, alert).orElse(null);
            if (rejectionReason != null) {
                retainMismatch(record, repairToken, rejectionReason);
                continue;
            }
            OutboxAlertProjectionPolicy.Projection projection = OutboxAlertProjectionPolicy.recovery(record);
            if (!writeProjection(record, projection)) {
                retainMismatch(record, repairToken, "ALERT_PROJECTION_REPAIR_FAILED");
                continue;
            }
            if (complete(record, repairToken)) {
                repaired++;
            }
        }
        metrics.recordOutboxProjectionMismatch(repository.countByProjectionMismatchTrue());
        return repaired;
    }

    boolean complete(TransactionalOutboxRecordDocument record, String repairToken) {
        Query complete = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(record.getStatus()),
                Criteria.where("projection_revision").is(record.getProjectionRevision()),
                Criteria.where("projection_repair_token").is(repairToken)
        ));
        Update clearMismatch = new Update()
                .unset("projection_mismatch")
                .unset("projection_mismatch_reason")
                .unset("projection_reconcile_after")
                .unset("projection_repair_token")
                .unset("projection_repair_claimed_at");
        return mongoTemplate.updateFirst(complete, clearMismatch, TransactionalOutboxRecordDocument.class)
                .getModifiedCount() == 1;
    }

    private boolean writeProjection(
            TransactionalOutboxRecordDocument record,
            OutboxAlertProjectionPolicy.Projection projection
    ) {
        if (record.getResourceId() == null || record.getResourceId().isBlank()) {
            return false;
        }
        try {
            return mongoTemplate.updateFirst(
                    projection.target(record.getResourceId()), projection.update(), AlertDocument.class
            ).getMatchedCount() == 1;
        } catch (org.springframework.dao.DataAccessException exception) {
            return false;
        }
    }

    private void retainMismatch(
            TransactionalOutboxRecordDocument record,
            String repairToken,
            String reason
    ) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(record.getStatus()),
                Criteria.where("projection_revision").is(record.getProjectionRevision()),
                Criteria.where("projection_repair_token").is(repairToken)
        ));
        Update update = new Update()
                .set("projection_mismatch", true)
                .set("projection_mismatch_reason", reason)
                .set("projection_reconcile_after", Instant.now().plus(RETRY_DELAY))
                .unset("projection_repair_token")
                .unset("projection_repair_claimed_at");
        mongoTemplate.updateFirst(query, update, TransactionalOutboxRecordDocument.class);
    }
}
