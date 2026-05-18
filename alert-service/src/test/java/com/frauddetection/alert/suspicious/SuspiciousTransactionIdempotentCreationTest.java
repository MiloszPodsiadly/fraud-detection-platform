package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.LATER;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.NOW;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionIdempotentCreationTest {

    @Test
    void sameScoredEventRetryDoesNotCreateDuplicateSuspiciousTransaction() {
        var repository = inMemoryRepository();
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        var service = new SuspiciousTransactionProjectionService(repository, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
        var event = alertWorthyEvent();

        var first = service.projectOrUpdate(event, null).orElseThrow();
        var second = service.projectOrUpdate(event, null).orElseThrow();

        assertThat(second.getSuspiciousTransactionId()).isEqualTo(first.getSuspiciousTransactionId());
    }

    @Test
    void idempotentUpdatePreservesCreatedAtAndRefreshesUpdatedAt() {
        var repository = inMemoryRepository();
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        var firstService = new SuspiciousTransactionProjectionService(repository, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
        var secondService = new SuspiciousTransactionProjectionService(repository, metrics, Clock.fixed(LATER, ZoneOffset.UTC));
        var event = alertWorthyEvent();

        var first = firstService.projectOrUpdate(event, null).orElseThrow();
        var second = secondService.projectOrUpdate(event, "alert-1").orElseThrow();

        assertThat(second.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(second.getUpdatedAt()).isEqualTo(LATER);
        assertThat(second.getStatus()).isEqualTo(SuspiciousTransactionStatus.ALERT_CREATED);
    }
}
