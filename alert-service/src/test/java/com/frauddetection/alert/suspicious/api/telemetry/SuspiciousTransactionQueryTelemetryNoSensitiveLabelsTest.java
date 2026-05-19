package com.frauddetection.alert.suspicious.api.telemetry;

import com.frauddetection.alert.suspicious.api.SuspiciousTransactionSearchQuery;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionQueryTelemetryNoSensitiveLabelsTest {

    @Test
    void sensitiveInputsNeverBecomeSnapshotFieldsOrMetricLabels() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("customerId", "customer-secret-123");
        params.add("linkedAlertId", "alert-secret-456");
        params.add("cursor", "opaque-cursor-secret");
        SuspiciousTransactionSearchQuery query = SuspiciousTransactionSearchQuery.from(params);
        SuspiciousTransactionQueryTelemetryClassifier classifier = new SuspiciousTransactionQueryTelemetryClassifier();
        SuspiciousTransactionQueryTelemetrySnapshot snapshot = classifier.search(
                query,
                "success",
                1,
                false,
                Duration.ofMillis(60)
        );

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new SuspiciousTransactionQueryTelemetryRecorder(registry, Duration.ofMillis(500)).record(snapshot);

        String snapshotText = snapshot.toString();
        List<String> meterText = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Object::toString)
                .toList();

        assertThat(snapshotText).doesNotContain(
                "customer-secret-123",
                "alert-secret-456",
                "opaque-cursor-secret",
                "transaction-123",
                "account-123",
                "source-event-123",
                "correlation-123",
                "HIGH_AMOUNT"
        );
        assertThat(String.join("\n", meterText)).doesNotContain(
                "customer-secret-123",
                "alert-secret-456",
                "opaque-cursor-secret",
                "transaction-123",
                "account-123",
                "source-event-123",
                "correlation-123",
                "HIGH_AMOUNT",
                "rawFilters",
                "rawException"
        );
    }
}
