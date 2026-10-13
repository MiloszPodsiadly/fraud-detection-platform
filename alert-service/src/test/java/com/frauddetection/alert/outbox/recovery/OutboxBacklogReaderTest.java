package com.frauddetection.alert.outbox.recovery;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.OutboxBacklogResponse;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxBacklogReaderTest {

    @Test
    void readsCanonicalBacklogAndRecordsItsSnapshot() {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        TransactionalOutboxRecordDocument oldest = new TransactionalOutboxRecordDocument();
        oldest.setCreatedAt(Instant.now().minusSeconds(120));
        when(repository.findTopByStatusInOrderByCreatedAtAsc(List.of(
                TransactionalOutboxStatus.PENDING,
                TransactionalOutboxStatus.PROCESSING,
                TransactionalOutboxStatus.FAILED_RETRYABLE
        ))).thenReturn(Optional.of(oldest));
        when(repository.countByStatus(TransactionalOutboxStatus.PENDING)).thenReturn(1L);
        when(repository.countByStatus(TransactionalOutboxStatus.PROCESSING)).thenReturn(2L);
        when(repository.countByStatus(TransactionalOutboxStatus.PUBLISH_ATTEMPTED)).thenReturn(3L);
        when(repository.countByStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)).thenReturn(4L);
        when(repository.countByStatus(TransactionalOutboxStatus.FAILED_RETRYABLE)).thenReturn(5L);
        when(repository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL)).thenReturn(6L);
        when(repository.countByStatus(TransactionalOutboxStatus.RECOVERY_REQUIRED)).thenReturn(7L);
        when(repository.countByProjectionMismatchTrue()).thenReturn(8L);
        when(repository.countByProjectionReconcileAfterIsNotNull()).thenReturn(9L);

        OutboxBacklogResponse response = new OutboxBacklogReader(repository, metrics).read();

        assertThat(response.pendingCount()).isEqualTo(1L);
        assertThat(response.processingCount()).isEqualTo(2L);
        assertThat(response.publishAttemptedCount()).isEqualTo(3L);
        assertThat(response.confirmationUnknownCount()).isEqualTo(4L);
        assertThat(response.failedRetryableCount()).isEqualTo(5L);
        assertThat(response.failedTerminalCount()).isEqualTo(6L);
        assertThat(response.recoveryRequiredCount()).isEqualTo(7L);
        assertThat(response.projectionMismatchCount()).isEqualTo(8L);
        assertThat(response.projectionReconciliationPendingCount()).isEqualTo(9L);
        assertThat(response.oldestPendingAgeSeconds()).isBetween(120L, 122L);
        ArgumentCaptor<OutboxBacklogResponse> recorded = ArgumentCaptor.forClass(OutboxBacklogResponse.class);
        verify(metrics).recordOutboxBacklog(recorded.capture());
        assertThat(recorded.getValue()).isEqualTo(response);
    }
}
