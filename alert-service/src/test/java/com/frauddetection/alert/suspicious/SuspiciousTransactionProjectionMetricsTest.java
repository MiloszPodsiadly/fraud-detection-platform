package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.enums.RiskLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuspiciousTransactionProjectionMetricsTest {

    @Test
    void createdUpdatedRemovedSkippedAndErrorMetricsUseLowCardinalityLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);
        var service = service(inMemoryRepository(), metrics);

        service.projectOrUpdate(alertWorthyEvent(), null);
        service.projectOrUpdate(alertWorthyEvent(), "alert-1");
        service.projectOrUpdate(
                SuspiciousTransactionTestSupport.event("event-low", "txn-1", false, RiskLevel.LOW, List.of()),
                null
        );
        service.projectOrUpdate(
                SuspiciousTransactionTestSupport.event("event-never-high", "txn-low", false, RiskLevel.LOW, List.of()),
                null
        );

        SuspiciousTransactionRepository failingRepository = mock(SuspiciousTransactionRepository.class);
        when(failingRepository.findByTransactionId(any())).thenThrow(new IllegalStateException("raw id"));
        org.assertj.core.api.Assertions.catchThrowable(
                () -> service(failingRepository, metrics).projectOrUpdate(alertWorthyEvent(), null)
        );

        assertThat(registry.get("fraud.suspicious_transaction.projection.created").tag("outcome", "created").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get("fraud.suspicious_transaction.projection.updated").tag("outcome", "updated").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get("fraud.suspicious_transaction.projection.removed").tag("outcome", "removed").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get("fraud.suspicious_transaction.projection.skipped").tag("reason", "non_alert_worthy").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get("fraud.suspicious_transaction.projection.error").tag("reason", "projection_error").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get("fraud.suspicious_transaction.projection.created").counter().getId().getTag("reason"))
                .isNull();
        assertThat(registry.get("fraud.suspicious_transaction.projection.updated").counter().getId().getTag("reason"))
                .isNull();
        assertThat(registry.get("fraud.suspicious_transaction.projection.removed").counter().getId().getTag("reason"))
                .isNull();
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags().stream().map(tag -> tag.getKey()))
                        .doesNotContain("transactionId", "sourceEventId", "customerId", "accountId", "correlationId", "reasonCode", "modelName"));
    }
}
