package com.frauddetection.alert.observability.outbox;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.observability.audit.AuditIntegrityMetricsRecorder;
import com.frauddetection.alert.observability.evidence.EvidenceSnapshotMetricsRecorder;
import com.frauddetection.alert.observability.regulated.RegulatedMutationRecoveryMetricsRecorder;
import com.frauddetection.alert.outbox.FraudAlertOutboxBacklogResponse;
import com.frauddetection.alert.outbox.OutboxBacklogResponse;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMetricsRecorderTest {

    private static final Set<String> METRIC_NAMES = Set.of(
            "fraud_platform_decision_outbox_failures_total",
            "outbox_pending_count",
            "outbox_processing_count",
            "outbox_confirmation_unknown_count",
            "outbox_failed_terminal_count",
            "outbox_projection_mismatch_count",
            "outbox_projection_reconciliation_pending_count",
            "outbox_oldest_pending_age_seconds",
            "outbox_projection_mismatch_total",
            "outbox_publish_attempt_total",
            "outbox_delivery_latency_seconds",
            "fraud_alert_outbox_pending_count",
            "fraud_alert_outbox_processing_count",
            "fraud_alert_outbox_publish_attempted_count",
            "fraud_alert_outbox_confirmation_unknown_count",
            "fraud_alert_outbox_failed_terminal_count",
            "fraud_alert_outbox_oldest_unresolved_age_seconds",
            "fraud_alert_outbox_publish_attempt_total",
            "fraud_alert_outbox_resolution_total"
    );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(EvidenceSnapshotMetricsRecorder.class)
            .withBean(AuditIntegrityMetricsRecorder.class)
            .withBean(OutboxMetricsRecorder.class)
            .withBean(RegulatedMutationRecoveryMetricsRecorder.class)
            .withBean(AlertServiceMetrics.class);

    @Test
    void compatibilityFacadeAndOwnedRecorderExposeIdenticalMetersAndValues() {
        SimpleMeterRegistry facadeRegistry = new SimpleMeterRegistry();
        SimpleMeterRegistry recorderRegistry = new SimpleMeterRegistry();
        AlertServiceMetrics facade = new AlertServiceMetrics(facadeRegistry);
        OutboxMetricsRecorder recorder = new OutboxMetricsRecorder(recorderRegistry);

        recordAll(facade);
        recordAll(recorder);

        assertThat(meterIdentities(facadeRegistry)).containsExactlyElementsOf(meterIdentities(recorderRegistry));
        for (String gauge : List.of(
                "outbox_pending_count",
                "outbox_projection_mismatch_count",
                "fraud_alert_outbox_publish_attempted_count",
                "fraud_alert_outbox_oldest_unresolved_age_seconds"
        )) {
            assertThat(facadeRegistry.get(gauge).gauge().value())
                    .isEqualTo(recorderRegistry.get(gauge).gauge().value());
        }
        assertThat(facadeRegistry.get("outbox_publish_attempt_total").tag("result", "SUCCESS").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void sharedCompositionRegistersEachMeterOnceAndRecordsOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetricsRecorder recorder = new OutboxMetricsRecorder(registry);
        AlertServiceMetrics facade = new AlertServiceMetrics(
                registry,
                new EvidenceSnapshotMetricsRecorder(registry),
                new AuditIntegrityMetricsRecorder(registry),
                recorder,
                new RegulatedMutationRecoveryMetricsRecorder(registry)
        );

        facade.recordOutboxPublishAttempt("SUCCESS");

        assertThat(registry.find("outbox_publish_attempt_total").meters()).hasSize(1);
        assertThat(registry.get("outbox_publish_attempt_total").tag("result", "SUCCESS").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void springWiresOneOutboxOwnerIntoCompatibilityFacade() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OutboxMetricsRecorder.class);
            assertThat(context).hasSingleBean(AlertServiceMetrics.class);

            context.getBean(AlertServiceMetrics.class).recordFraudAlertOutboxResolution("PUBLISHED");

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.find("fraud_alert_outbox_resolution_total").meters()).hasSize(1);
            assertThat(registry.get("fraud_alert_outbox_resolution_total")
                    .tag("resolution", "PUBLISHED")
                    .counter()
                    .count()).isEqualTo(1.0);
        });
    }

    private void recordAll(AlertServiceMetrics metrics) {
        metrics.recordDecisionOutboxPublishConfirmationFailed();
        metrics.recordOutboxBacklog(outboxBacklog());
        metrics.recordOutboxProjectionMismatch(7);
        metrics.recordOutboxPublishAttempt("SUCCESS");
        metrics.recordOutboxDeliveryLatency(Duration.ofMillis(25));
        metrics.recordFraudAlertOutboxBacklog(fraudAlertBacklog());
        metrics.recordFraudAlertOutboxPublishAttempt("PUBLISHED");
        metrics.recordFraudAlertOutboxResolution("PUBLISHED");
    }

    private void recordAll(OutboxMetricsRecorder recorder) {
        recorder.recordDecisionPublishConfirmationFailed();
        recorder.recordBacklog(outboxBacklog());
        recorder.recordProjectionMismatch(7);
        recorder.recordPublishAttempt("SUCCESS");
        recorder.recordDeliveryLatency(Duration.ofMillis(25));
        recorder.recordFraudAlertBacklog(fraudAlertBacklog());
        recorder.recordFraudAlertPublishAttempt("PUBLISHED");
        recorder.recordFraudAlertResolution("PUBLISHED");
    }

    private OutboxBacklogResponse outboxBacklog() {
        return new OutboxBacklogResponse(2, 3, 4, 5, 6, 7, 8, 9, 10, 11L);
    }

    private FraudAlertOutboxBacklogResponse fraudAlertBacklog() {
        return new FraudAlertOutboxBacklogResponse(12, 13, 14, 15, 16, 17L);
    }

    private List<String> meterIdentities(MeterRegistry registry) {
        return registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> METRIC_NAMES.contains(id.getName()))
                .map(id -> id.getName() + "|" + id.getType() + "|" + id.getTags())
                .sorted()
                .toList();
    }
}
