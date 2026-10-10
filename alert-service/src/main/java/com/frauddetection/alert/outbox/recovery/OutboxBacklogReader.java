package com.frauddetection.alert.outbox.recovery;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.OutboxBacklogResponse;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class OutboxBacklogReader {

    private static final List<TransactionalOutboxStatus> PENDING_STATUSES = List.of(
            TransactionalOutboxStatus.PENDING,
            TransactionalOutboxStatus.PROCESSING,
            TransactionalOutboxStatus.FAILED_RETRYABLE
    );

    private final TransactionalOutboxRecordRepository repository;
    private final AlertServiceMetrics metrics;

    public OutboxBacklogReader(
            TransactionalOutboxRecordRepository repository,
            AlertServiceMetrics metrics
    ) {
        this.repository = repository;
        this.metrics = metrics;
    }

    public OutboxBacklogResponse read() {
        Long oldestPendingAge = repository.findTopByStatusInOrderByCreatedAtAsc(PENDING_STATUSES)
                .map(this::ageSeconds)
                .orElse(null);
        OutboxBacklogResponse response = new OutboxBacklogResponse(
                repository.countByStatus(TransactionalOutboxStatus.PENDING),
                repository.countByStatus(TransactionalOutboxStatus.PROCESSING),
                repository.countByStatus(TransactionalOutboxStatus.PUBLISH_ATTEMPTED),
                repository.countByStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN),
                repository.countByStatus(TransactionalOutboxStatus.FAILED_RETRYABLE),
                repository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL),
                repository.countByStatus(TransactionalOutboxStatus.RECOVERY_REQUIRED),
                repository.countByProjectionMismatchTrue(),
                repository.countByProjectionReconcileAfterIsNotNull(),
                oldestPendingAge
        );
        metrics.recordOutboxBacklog(response);
        return response;
    }

    private long ageSeconds(TransactionalOutboxRecordDocument record) {
        Instant createdAt = record.getCreatedAt();
        if (createdAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(createdAt, Instant.now()).toSeconds());
    }
}
