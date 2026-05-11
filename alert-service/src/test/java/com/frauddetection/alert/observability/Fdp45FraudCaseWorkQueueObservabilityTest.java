package com.frauddetection.alert.observability;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueObservabilityTest {

    @Test
    void shouldUseOnlyLowCardinalityWorkQueueMetricLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordFraudCaseWorkQueueRequest("success");
        metrics.recordFraudCaseWorkQueueRequest("raw-case-123-user-456");
        metrics.recordFraudCaseWorkQueueQuery("success", "createdAt");
        metrics.recordFraudCaseWorkQueueQuery("invalid_sort", "customerId");
        metrics.recordFraudCaseWorkQueuePageSize(50);

        assertThat(registry.get("fraud_case_work_queue_requests_total").tag("outcome", "success").meter().getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("endpoint_family", "outcome");
        assertThat(registry.get("fraud_case_work_queue_query_total").tag("sort_field", "createdAt").meter().getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("outcome", "sort_field");
        assertThat(registry.get("fraud_case_work_queue_query_total").tag("sort_field", "default").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get("fraud_case_work_queue_page_size_bucket").summary().count()).isEqualTo(1L);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                        .doesNotContain("case-123")
                        .doesNotContain("user-456")
                        .doesNotContain("customerId")
                        .doesNotContain("assignee")
                        .doesNotContain("exception")
                        .doesNotContain("stack"));
    }
}
