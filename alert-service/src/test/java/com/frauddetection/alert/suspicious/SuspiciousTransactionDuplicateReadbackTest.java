package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.LATER;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.NOW;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspiciousTransactionDuplicateReadbackTest {

    @Test
    void duplicateKeyOnSaveReadsBackExistingSuspiciousTransaction() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        TransactionScoredEvent event = alertWorthyEvent();
        SuspiciousTransactionDocument existing = existingDocument(event, null);
        var service = service(repository, registry);

        when(repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        Optional<SuspiciousTransactionDocument> result = service.projectOrUpdate(event, null);

        assertThat(result).containsSame(existing);
        verify(repository, times(2)).findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId());
        assertDuplicateRetryMetric(registry, SuspiciousTransactionStatus.NEW);
        assertProjectionErrorMetricAbsent(registry);
    }

    @Test
    void duplicateKeyReadbackWithLinkedAlertIdUpdatesExistingUnlinkedDocument() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        TransactionScoredEvent event = alertWorthyEvent();
        SuspiciousTransactionDocument existing = existingDocument(event, null);
        var service = service(repository, registry);

        when(repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SuspiciousTransactionDocument result = service.projectOrUpdate(event, "alert-1").orElseThrow();

        assertThat(result.getLinkedAlertId()).isEqualTo("alert-1");
        assertThat(result.getStatus()).isEqualTo(SuspiciousTransactionStatus.ALERT_CREATED);
        assertThat(result.getUpdatedAt()).isEqualTo(LATER);
        verify(repository, times(2)).save(any(SuspiciousTransactionDocument.class));
        assertDuplicateRetryMetric(registry, SuspiciousTransactionStatus.ALERT_CREATED);
        assertProjectionErrorMetricAbsent(registry);
    }

    @Test
    void duplicateKeyReadbackDoesNotOverwriteExistingLinkedAlertId() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        TransactionScoredEvent event = alertWorthyEvent();
        SuspiciousTransactionDocument existing = existingDocument(event, "alert-existing");
        var service = service(repository, registry);

        when(repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        SuspiciousTransactionDocument result = service.projectOrUpdate(event, "alert-new").orElseThrow();

        assertThat(result.getLinkedAlertId()).isEqualTo("alert-existing");
        assertThat(result.getStatus()).isEqualTo(SuspiciousTransactionStatus.ALERT_CREATED);
        verify(repository).save(any(SuspiciousTransactionDocument.class));
        assertDuplicateRetryMetric(registry, SuspiciousTransactionStatus.ALERT_CREATED);
        assertProjectionErrorMetricAbsent(registry);
    }

    @Test
    void duplicateKeyReadbackWithoutLinkedAlertIdReturnsExistingAsIs() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        TransactionScoredEvent event = alertWorthyEvent();
        SuspiciousTransactionDocument existing = existingDocument(event, null);
        var service = service(repository, registry);

        when(repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        SuspiciousTransactionDocument result = service.projectOrUpdate(event, null).orElseThrow();

        assertThat(result.getLinkedAlertId()).isNull();
        assertThat(result.getStatus()).isEqualTo(SuspiciousTransactionStatus.NEW);
        verify(repository).save(any(SuspiciousTransactionDocument.class));
        assertDuplicateRetryMetric(registry, SuspiciousTransactionStatus.NEW);
        assertProjectionErrorMetricAbsent(registry);
    }

    @Test
    void duplicateKeyReadbackFailureRecordsProjectionError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        TransactionScoredEvent event = alertWorthyEvent();
        var service = service(repository, registry);

        when(repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        Optional<SuspiciousTransactionDocument> result = service.projectOrUpdate(event, null);

        assertThat(result).isEmpty();
        assertProjectionErrorMetric(registry, "duplicate_readback_missing");
    }

    @Test
    void duplicateKeyReadbackExceptionRecordsProjectionError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        TransactionScoredEvent event = alertWorthyEvent();
        var service = service(repository, registry);

        when(repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId()))
                .thenReturn(Optional.empty())
                .thenThrow(new IllegalStateException("readback failed"));
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        Optional<SuspiciousTransactionDocument> result = service.projectOrUpdate(event, null);

        assertThat(result).isEmpty();
        assertProjectionErrorMetric(registry, "duplicate_readback_failed");
    }

    @Test
    void genericSaveRuntimeExceptionStillRecordsProjectionError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        TransactionScoredEvent event = alertWorthyEvent();
        var service = service(repository, registry);

        when(repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId()))
                .thenReturn(Optional.empty());
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new IllegalStateException("save failed"));

        Optional<SuspiciousTransactionDocument> result = service.projectOrUpdate(event, null);

        assertThat(result).isEmpty();
        assertProjectionErrorMetric(registry, "projection_error");
        assertThat(registry.find("fraud.suspicious_transaction.projection.duplicate_retry").counter()).isNull();
    }

    @Test
    void duplicateKeyReadbackUpdateFailureRecordsProjectionError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        TransactionScoredEvent event = alertWorthyEvent();
        SuspiciousTransactionDocument existing = existingDocument(event, null);
        var service = service(repository, registry);

        when(repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenThrow(new IllegalStateException("readback update failed"));

        Optional<SuspiciousTransactionDocument> result = service.projectOrUpdate(event, "alert-1");

        assertThat(result).isEmpty();
        assertProjectionErrorMetric(registry, "duplicate_readback_failed");
    }

    private SuspiciousTransactionProjectionService service(
            SuspiciousTransactionRepository repository,
            SimpleMeterRegistry registry
    ) {
        return new SuspiciousTransactionProjectionService(
                repository,
                new AlertServiceMetrics(registry),
                Clock.fixed(LATER, ZoneOffset.UTC)
        );
    }

    private SuspiciousTransactionDocument existingDocument(TransactionScoredEvent event, String linkedAlertId) {
        SuspiciousTransactionDocument document = new SuspiciousTransactionDocument();
        document.setSuspiciousTransactionId("suspicious-1");
        document.setTransactionId(event.transactionId());
        document.setSourceEventId(event.eventId());
        document.setLinkedAlertId(linkedAlertId);
        document.setStatus(linkedAlertId == null ? SuspiciousTransactionStatus.NEW : SuspiciousTransactionStatus.ALERT_CREATED);
        document.setCreatedAt(NOW);
        document.setUpdatedAt(NOW);
        return document;
    }

    private void assertDuplicateRetryMetric(SimpleMeterRegistry registry, SuspiciousTransactionStatus status) {
        assertThat(registry.get("fraud.suspicious_transaction.projection.duplicate_retry")
                .tag("outcome", "duplicate_retry")
                .tag("status", status.name())
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private void assertProjectionErrorMetric(SimpleMeterRegistry registry, String reason) {
        assertThat(registry.get("fraud.suspicious_transaction.projection.error")
                .tag("reason", reason)
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private void assertProjectionErrorMetricAbsent(SimpleMeterRegistry registry) {
        assertThat(registry.find("fraud.suspicious_transaction.projection.error").counter()).isNull();
    }
}
