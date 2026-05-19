package com.frauddetection.alert.suspicious.api.telemetry;

import com.frauddetection.alert.suspicious.api.SuspiciousTransactionSearchQuery;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionQueryTelemetryClassifierTest {

    private final SuspiciousTransactionQueryTelemetryClassifier classifier =
            new SuspiciousTransactionQueryTelemetryClassifier();

    @Test
    void classifiesSearchQueryShapesWithoutRawFilterValues() {
        assertThat(classifier.search(query(), "success", 0, false, Duration.ofMillis(10)).queryShape())
                .isEqualTo("unfiltered");
        assertThat(classifier.search(query("status", "NEW"), "success", 0, false, Duration.ofMillis(10)).queryShape())
                .isEqualTo("status");
        assertThat(classifier.search(query("riskLevel", "HIGH"), "success", 0, false, Duration.ofMillis(10)).queryShape())
                .isEqualTo("risk");
        assertThat(classifier.search(query("customerId", "customer-secret-1"), "success", 0, false, Duration.ofMillis(10)).queryShape())
                .isEqualTo("customer");
        assertThat(classifier.search(query("linkedAlertId", "alert-secret-1"), "success", 0, false, Duration.ofMillis(10)).queryShape())
                .isEqualTo("linked_alert");
        assertThat(classifier.search(query("detectedFrom", "2026-05-18T10:00:00Z"), "success", 0, false, Duration.ofMillis(10)).queryShape())
                .isEqualTo("date_range");
    }

    @Test
    void classifiesMultiFilterAndBuckets() {
        SuspiciousTransactionQueryTelemetrySnapshot snapshot = classifier.search(
                query("status", "NEW", "riskLevel", "HIGH", "customerId", "customer-secret-1"),
                "success",
                51,
                true,
                Duration.ofMillis(501)
        );

        assertThat(snapshot.queryShape()).isEqualTo("multi_filter");
        assertThat(snapshot.filterCountBucket()).isEqualTo("3_plus");
        assertThat(snapshot.resultSizeBucket()).isEqualTo("51_100");
        assertThat(snapshot.hasNext()).isEqualTo("true");
        assertThat(snapshot.cursorUsed()).isEqualTo("false");
        assertThat(snapshot.durationBucket()).isEqualTo("500ms_plus");
    }

    @Test
    void classifiesReadAsIdLookupWithUnknownCursorAndHasNext() {
        SuspiciousTransactionQueryTelemetrySnapshot snapshot = classifier.read("not_found", 0, Duration.ofMillis(100));

        assertThat(snapshot.endpoint()).isEqualTo("read");
        assertThat(snapshot.outcome()).isEqualTo("not_found");
        assertThat(snapshot.queryShape()).isEqualTo("id_lookup");
        assertThat(snapshot.resultSizeBucket()).isEqualTo("0");
        assertThat(snapshot.hasNext()).isEqualTo("unknown");
        assertThat(snapshot.cursorUsed()).isEqualTo("unknown");
        assertThat(snapshot.durationBucket()).isEqualTo("100_250ms");
    }

    @Test
    void exposesOnlyBoundedMetricTags() {
        SuspiciousTransactionQueryTelemetrySnapshot snapshot = classifier.search(
                query("customerId", "customer-secret-1", "cursor", "opaque-cursor-secret"),
                "success",
                8,
                false,
                Duration.ofMillis(49)
        );

        assertThat(snapshot.metricTags().stream().map(tag -> tag.getKey()).toList())
                .containsExactlyInAnyOrder(
                        "endpoint",
                        "outcome",
                        "queryShape",
                        "filterCountBucket",
                        "resultSizeBucket",
                        "hasNext",
                        "cursorUsed"
                );
        assertThat(snapshot.toString())
                .doesNotContain("customer-secret-1", "opaque-cursor-secret");
    }

    private SuspiciousTransactionSearchQuery query(String... pairs) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return SuspiciousTransactionSearchQuery.from(params);
    }
}
