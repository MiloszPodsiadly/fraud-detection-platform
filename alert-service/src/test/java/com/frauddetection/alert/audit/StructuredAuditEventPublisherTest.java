package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredAuditEventPublisherTest {

    @Test
    void shouldRecordAuditMetricWithLowCardinalityTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
        StructuredAuditEventPublisher publisher = new StructuredAuditEventPublisher(metrics);

        publisher.publish(new AuditEvent(
                new AuditActor("analyst-1", Set.of("ANALYST"), Set.of("alert:read")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                Instant.parse("2026-04-23T10:00:00Z"),
                "corr-1",
                AuditOutcome.SUCCESS,
                null
        ));

        assertThat(meterRegistry.get("fraud.alert.audit.events")
                .tags("action", "submit_analyst_decision", "outcome", "success")
                .counter()
                .count()).isEqualTo(1.0d);
    }
}
