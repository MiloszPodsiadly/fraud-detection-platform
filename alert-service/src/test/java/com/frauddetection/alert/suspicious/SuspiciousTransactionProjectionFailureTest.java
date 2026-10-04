package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.LATER;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuspiciousTransactionProjectionFailureTest {

    @Test
    void duplicateCurrentProjectionWriteFailsTheOccurrenceTransactionForKafkaRetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        var event = alertWorthyEvent();
        when(repository.findByTransactionId(event.transactionId())).thenReturn(Optional.empty());
        when(repository.save(any(SuspiciousTransactionDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service(repository, registry).projectOrUpdate(event, null))
                .isInstanceOf(SuspiciousTransactionProjectionException.class)
                .hasMessage("SUSPICIOUS_TRANSACTION_PROJECTION_FAILED");
        assertProjectionError(registry);
    }

    @Test
    void storeFailureFailsTheOccurrenceTransactionForKafkaRetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        var event = alertWorthyEvent();
        when(repository.findByTransactionId(event.transactionId()))
                .thenThrow(new IllegalStateException("raw store detail"));

        assertThatThrownBy(() -> service(repository, registry).projectOrUpdate(event, null))
                .isInstanceOf(SuspiciousTransactionProjectionException.class)
                .hasMessage("SUSPICIOUS_TRANSACTION_PROJECTION_FAILED")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertProjectionError(registry);
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

    private void assertProjectionError(SimpleMeterRegistry registry) {
        assertThat(registry.get("fraud.suspicious_transaction.projection.error")
                .tag("reason", "projection_error")
                .counter()
                .count()).isEqualTo(1.0d);
    }
}
