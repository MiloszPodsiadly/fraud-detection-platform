package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionReadMetricsTest {

    @Test
    void recordsReadAndSearchMetricsWithLowCardinalityLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionApiRead("success", "NEW", "HIGH");
        metrics.recordSuspiciousTransactionApiRead("not_found", "ANY", "ANY");
        metrics.recordSuspiciousTransactionApiSearch("success", "ALERT_CREATED", "CRITICAL");
        metrics.recordSuspiciousTransactionApiSearch("validation_error", "ANY", "ANY");

        assertThat(registry.get("fraud.suspicious_transaction.api.read")
                .tag("outcome", "success")
                .tag("status", "NEW")
                .tag("riskLevel", "HIGH")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("fraud.suspicious_transaction.api.read")
                .tag("outcome", "not_found")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("fraud.suspicious_transaction.api.search")
                .tag("outcome", "validation_error")
                .counter()
                .count()).isEqualTo(1.0);

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().startsWith("fraud.suspicious_transaction.api."))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .containsOnly("outcome", "status", "riskLevel"));
    }

    @Test
    void searchMetricsRemainLowCardinalityAfterSliceMigration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionApiSearch("success", "ANY", "ANY");

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("fraud.suspicious_transaction.api.search"))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .doesNotContain("totalElements", "hasNext", "page", "size", "customerId", "transactionId")
                        .containsOnly("outcome", "status", "riskLevel"));
    }
}
