package com.frauddetection.alert.observability.outbox;

import com.frauddetection.alert.outbox.FraudAlertOutboxBacklogResponse;
import com.frauddetection.alert.outbox.OutboxBacklogResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicLong outboxPending = new AtomicLong(0);
    private final AtomicLong outboxProcessing = new AtomicLong(0);
    private final AtomicLong outboxConfirmationUnknown = new AtomicLong(0);
    private final AtomicLong outboxFailedTerminal = new AtomicLong(0);
    private final AtomicLong outboxProjectionMismatch = new AtomicLong(0);
    private final AtomicLong outboxProjectionReconciliationPending = new AtomicLong(0);
    private final AtomicLong outboxOldestPendingAgeSeconds = new AtomicLong(0);
    private final AtomicLong fraudAlertOutboxPending = new AtomicLong(0);
    private final AtomicLong fraudAlertOutboxProcessing = new AtomicLong(0);
    private final AtomicLong fraudAlertOutboxPublishAttempted = new AtomicLong(0);
    private final AtomicLong fraudAlertOutboxConfirmationUnknown = new AtomicLong(0);
    private final AtomicLong fraudAlertOutboxFailedTerminal = new AtomicLong(0);
    private final AtomicLong fraudAlertOutboxOldestUnresolvedAgeSeconds = new AtomicLong(0);

    public OutboxMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("outbox_pending_count", outboxPending, AtomicLong::get).register(meterRegistry);
        Gauge.builder("outbox_processing_count", outboxProcessing, AtomicLong::get).register(meterRegistry);
        Gauge.builder("outbox_confirmation_unknown_count", outboxConfirmationUnknown, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("outbox_failed_terminal_count", outboxFailedTerminal, AtomicLong::get).register(meterRegistry);
        Gauge.builder("outbox_projection_mismatch_count", outboxProjectionMismatch, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder(
                "outbox_projection_reconciliation_pending_count",
                outboxProjectionReconciliationPending,
                AtomicLong::get
        ).register(meterRegistry);
        Gauge.builder("outbox_oldest_pending_age_seconds", outboxOldestPendingAgeSeconds, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("fraud_alert_outbox_pending_count", fraudAlertOutboxPending, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("fraud_alert_outbox_processing_count", fraudAlertOutboxProcessing, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("fraud_alert_outbox_publish_attempted_count", fraudAlertOutboxPublishAttempted, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder(
                "fraud_alert_outbox_confirmation_unknown_count",
                fraudAlertOutboxConfirmationUnknown,
                AtomicLong::get
        ).register(meterRegistry);
        Gauge.builder("fraud_alert_outbox_failed_terminal_count", fraudAlertOutboxFailedTerminal, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder(
                "fraud_alert_outbox_oldest_unresolved_age_seconds",
                fraudAlertOutboxOldestUnresolvedAgeSeconds,
                AtomicLong::get
        ).register(meterRegistry);
    }

    public void recordDecisionPublishConfirmationFailed() {
        counter(
                "fraud_platform_decision_outbox_failures_total",
                "reason", "OUTBOX_PUBLISH_CONFIRMATION_FAILED"
        ).increment();
    }

    public void recordBacklog(OutboxBacklogResponse response) {
        outboxPending.set(Math.max(0L, response.pendingCount()));
        outboxProcessing.set(Math.max(0L, response.processingCount()));
        outboxConfirmationUnknown.set(Math.max(0L, response.confirmationUnknownCount()));
        outboxFailedTerminal.set(Math.max(0L, response.failedTerminalCount()));
        outboxProjectionMismatch.set(Math.max(0L, response.projectionMismatchCount()));
        outboxProjectionReconciliationPending.set(Math.max(0L, response.projectionReconciliationPendingCount()));
        outboxOldestPendingAgeSeconds.set(
                response.oldestPendingAgeSeconds() == null ? 0L : Math.max(0L, response.oldestPendingAgeSeconds())
        );
    }

    public void recordProjectionMismatch(long mismatchCount) {
        outboxProjectionMismatch.set(Math.max(0L, mismatchCount));
        counter("outbox_projection_mismatch_total", "reason", "PROJECTION_UPDATE_FAILED").increment();
    }

    public void recordPublishAttempt(String result) {
        counter("outbox_publish_attempt_total", "result", normalizePublishResult(result)).increment();
    }

    public void recordDeliveryLatency(Duration latency) {
        Timer.builder("outbox_delivery_latency_seconds")
                .register(meterRegistry)
                .record(latency == null || latency.isNegative() ? Duration.ZERO : latency);
    }

    public void recordFraudAlertBacklog(FraudAlertOutboxBacklogResponse response) {
        fraudAlertOutboxPending.set(Math.max(0L, response.pendingCount()));
        fraudAlertOutboxProcessing.set(Math.max(0L, response.processingCount()));
        fraudAlertOutboxPublishAttempted.set(Math.max(0L, response.publishAttemptedCount()));
        fraudAlertOutboxConfirmationUnknown.set(Math.max(0L, response.confirmationUnknownCount()));
        fraudAlertOutboxFailedTerminal.set(Math.max(0L, response.failedTerminalCount()));
        fraudAlertOutboxOldestUnresolvedAgeSeconds.set(
                response.oldestUnresolvedAgeSeconds() == null
                        ? 0L
                        : Math.max(0L, response.oldestUnresolvedAgeSeconds())
        );
    }

    public void recordFraudAlertPublishAttempt(String outcome) {
        counter(
                "fraud_alert_outbox_publish_attempt_total",
                "outcome", normalizeFraudAlertPublishOutcome(outcome)
        ).increment();
    }

    public void recordFraudAlertResolution(String resolution) {
        counter(
                "fraud_alert_outbox_resolution_total",
                "resolution", normalizeFraudAlertResolution(resolution)
        ).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private String normalizePublishResult(String result) {
        return switch (result) {
            case "SUCCESS", "FAILED", "CONFIRMATION_UNKNOWN" -> result;
            default -> "FAILED";
        };
    }

    private String normalizeFraudAlertPublishOutcome(String outcome) {
        return switch (outcome) {
            case "PUBLISHED", "CONFIRMATION_UNKNOWN", "PRE_SEND_STATE_WRITE_FAILED", "PRE_SEND_TERMINAL" -> outcome;
            default -> "UNKNOWN";
        };
    }

    private String normalizeFraudAlertResolution(String resolution) {
        return switch (resolution) {
            case "PUBLISHED", "CONFIRMED_NOT_DELIVERED" -> resolution;
            default -> "UNKNOWN";
        };
    }
}
