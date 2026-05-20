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

    @Test
    void readMetricsDoNotIncludeCustomerOrAccountIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionApiRead("success", "NEW", "HIGH");

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("fraud.suspicious_transaction.api.read"))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .doesNotContain("customerId", "accountId", "transactionId", "suspiciousTransactionId")
                        .containsOnly("outcome", "status", "riskLevel"));
    }

    @Test
    void singleReadMetricsLabelsOnlyOutcomeStatusRiskLevel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionApiRead("success", "ALERT_CREATED", "CRITICAL");

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("fraud.suspicious_transaction.api.read"))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .containsOnly("outcome", "status", "riskLevel"));
    }

    @Test
    void singleReadMetricsDoNotUseSuspiciousTransactionId() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionApiRead("success", "NEW", "HIGH");

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("fraud.suspicious_transaction.api.read"))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .doesNotContain("suspiciousTransactionId", "cursor", "customerId", "accountId")
                        .containsOnly("outcome", "status", "riskLevel"));
    }

    @Test
    void notFoundReadMetricsDoNotUseSuspiciousTransactionId() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionApiRead("not_found", "ANY", "ANY");

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("fraud.suspicious_transaction.api.read"))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .doesNotContain("suspiciousTransactionId")
                        .containsOnly("outcome", "status", "riskLevel"));
    }

    @Test
    void searchMetricsDoNotIncludeRawFilters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionApiSearch("success", "ALERT_CREATED", "CRITICAL");

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("fraud.suspicious_transaction.api.search"))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .doesNotContain("rawFilters", "customerId", "accountId", "linkedAlertId", "transactionId")
                        .containsOnly("outcome", "status", "riskLevel"));
    }

    @Test
    void summarySuccessRecordsLowCardinalityMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionSummaryRead("success", "FRESH");

        assertThat(registry.get("fraud.suspicious_transaction.summary.read")
                .tag("outcome", "success")
                .tag("freshness", "fresh")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void summaryStaleRecordsLowCardinalityMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionSummaryRead("stale", "STALE");

        assertThat(registry.get("fraud.suspicious_transaction.summary.read")
                .tag("outcome", "stale")
                .tag("freshness", "stale")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void summaryUnavailableRecordsLowCardinalityMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionSummaryRead("unavailable", "UNAVAILABLE");

        assertThat(registry.get("fraud.suspicious_transaction.summary.read")
                .tag("outcome", "unavailable")
                .tag("freshness", "unavailable")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void summaryMetricDoesNotUseCountAsLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionSummaryRead("98", "98");

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("fraud.suspicious_transaction.summary.read"))
                .forEach(id -> {
                    assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                            .containsOnly("outcome", "freshness")
                            .doesNotContain("count", "total", "cursor", "suspiciousTransactionId");
                    assertThat(id.getTag("outcome")).isEqualTo("error");
                    assertThat(id.getTag("freshness")).isEqualTo("unavailable");
                });
    }

    @Test
    void summaryMetricDoesNotUseExceptionMessageAsLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordSuspiciousTransactionSummaryRead("mongo unavailable for customer-123", "stacktrace");

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals("fraud.suspicious_transaction.summary.read"))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getValue()).toList())
                        .containsExactlyInAnyOrder("error", "unavailable")
                        .doesNotContain("mongo unavailable for customer-123", "stacktrace", "customer-123"));
    }
}
