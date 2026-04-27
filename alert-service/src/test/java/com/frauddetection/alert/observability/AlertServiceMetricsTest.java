package com.frauddetection.alert.observability;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);

    @Test
    void shouldUseOnlyBoundedLifecycleMetricLabels() {
        metrics.recordGovernanceAdvisoryLifecycle(
                "ACKNOWLEDGED",
                "python-logistic-fraud-model",
                "2026-04-21.trained.v1"
        );

        Meter meter = meterRegistry.get("fraud_ml_governance_advisory_lifecycle_total").meter();

        assertThat(meter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("lifecycle_status", "model_name", "model_version");
        assertThat(meter.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("event_id", "actor_id", "audit_id", "endpoint", "reason", "exception");
    }

    @Test
    void shouldUseNoDynamicLabelsForAnalyticsMetrics() {
        metrics.recordGovernanceAnalyticsRequest(7);
        metrics.recordGovernanceAnalyticsOutcome("AVAILABLE", Duration.ofMillis(25));

        Meter requestMeter = meterRegistry.get("fraud_ml_governance_analytics_requests_total").meter();
        Meter windowMeter = meterRegistry.get("fraud_ml_governance_analytics_window_days").meter();
        Meter statusMeter = meterRegistry.get("fraud_ml_governance_analytics_status_total").meter();
        Meter latencyMeter = meterRegistry.get("fraud_ml_governance_analytics_latency_seconds").meter();

        assertThat(requestMeter.getId().getTags()).isEmpty();
        assertThat(windowMeter.getId().getTags()).isEmpty();
        assertThat(statusMeter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(statusMeter.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("AVAILABLE");
        assertThat(latencyMeter.getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("fraud_ml_governance_analytics_window_days").gauge().value()).isEqualTo(7.0);
        assertThat(meterRegistry.get("fraud_ml_governance_analytics_latency_seconds").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("fraud_ml_governance_analytics_status_total").tag("status", "AVAILABLE").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldBoundAnalyticsStatusLabels() {
        metrics.recordGovernanceAnalyticsOutcome("event-123", Duration.ofMillis(1));

        Meter statusMeter = meterRegistry.get("fraud_ml_governance_analytics_status_total").meter();

        assertThat(statusMeter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(statusMeter.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("UNAVAILABLE");
    }

    @Test
    void shouldUseLowCardinalityPlatformAuditPersistenceMetricLabels() {
        metrics.recordPlatformAuditEventPersisted(AuditAction.SUBMIT_ANALYST_DECISION, AuditOutcome.SUCCESS);
        metrics.recordPlatformAuditPersistenceFailure(AuditAction.SUBMIT_ANALYST_DECISION);
        metrics.recordPlatformAuditReadRequest("AVAILABLE");
        metrics.recordAuditIntegrityCheck("INVALID");
        metrics.recordAuditIntegrityViolation("EVENT_HASH_MISMATCH");
        metrics.recordExternalAnchorPublished("local-file", "PUBLISHED");
        metrics.recordExternalAnchorPublishFailed("local-file", "IO_ERROR");
        metrics.recordExternalAnchorLag(Duration.ofSeconds(3));
        metrics.recordExternalIntegrityCheck("PARTIAL");
        metrics.recordEvidenceExport("PARTIAL");
        metrics.recordEvidenceExportRateLimited();

        Meter persisted = meterRegistry.get("fraud_platform_audit_events_persisted_total").meter();
        Meter failures = meterRegistry.get("fraud_platform_audit_persistence_failures_total").meter();
        Meter reads = meterRegistry.get("fraud_platform_audit_read_requests_total").meter();
        Meter integrityChecks = meterRegistry.get("fraud_platform_audit_integrity_checks_total").meter();
        Meter integrityViolations = meterRegistry.get("fraud_platform_audit_integrity_violations_total").meter();
        Meter externalAnchorPublished = meterRegistry.get("fraud_platform_audit_external_anchor_published_total").meter();
        Meter externalAnchorFailures = meterRegistry.get("fraud_platform_audit_external_anchor_publish_failed_total").meter();
        Meter externalAnchorLag = meterRegistry.get("fraud_platform_audit_external_anchor_lag_seconds").meter();
        Meter externalIntegrityChecks = meterRegistry.get("fraud_platform_audit_external_integrity_checks_total").meter();
        Meter evidenceExports = meterRegistry.get("fraud_platform_audit_evidence_exports_total").meter();
        Meter evidenceExportRateLimited = meterRegistry.get("fraud_platform_audit_evidence_export_rate_limited_total").meter();

        assertThat(persisted.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("event_type", "outcome");
        assertThat(failures.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("event_type");
        assertThat(reads.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(integrityChecks.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(integrityViolations.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("violation_type");
        assertThat(externalAnchorPublished.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("sink", "status");
        assertThat(externalAnchorFailures.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("sink", "reason");
        assertThat(externalAnchorLag.getId().getTags()).isEmpty();
        assertThat(externalIntegrityChecks.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(evidenceExports.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("status");
        assertThat(evidenceExports.getId().getTags())
                .extracting(Tag::getValue)
                .containsExactly("PARTIAL");
        assertThat(evidenceExportRateLimited.getId().getTags()).isEmpty();
        assertThat(persisted.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("actor_id", "resource_id", "audit_event_id", "hash", "exception", "message");
        assertThat(externalAnchorPublished.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("actor_id", "resource_id", "audit_event_id", "hash", "path", "exception", "message");
    }
}
