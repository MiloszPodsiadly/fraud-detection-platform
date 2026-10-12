package com.frauddetection.alert.observability.engineintelligence;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionDisposition;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceRecoveryOutcome;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackReadMetricReason;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackSubmitMetricReason;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceProjectionMetricReason;
import com.frauddetection.alert.engineintelligence.observability.MlPredictionEvidenceProjectionMetricReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EngineIntelligenceMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicLong pendingProjection = new AtomicLong(0);
    private final AtomicLong unresolvedProjection = new AtomicLong(0);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0);

    public EngineIntelligenceMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("engine_intelligence_pending_projection_count", pendingProjection, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("engine_intelligence_unresolved_projection_count", unresolvedProjection, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder(
                "engine_intelligence_oldest_pending_projection_age_seconds",
                oldestPendingAgeSeconds,
                AtomicLong::get
        ).register(meterRegistry);
    }

    public void recordProjectionAttempt() {
        counter("engine_intelligence_projection_attempt_total").increment();
    }

    public void recordProjectionSuccess() {
        counter("engine_intelligence_projection_success_total").increment();
    }

    public void recordProjectionOmitted(EngineIntelligenceProjectionMetricReason reason) {
        counter("engine_intelligence_projection_omitted_total", "reason", projectionReason(reason)).increment();
    }

    public void recordProjectionFailure(EngineIntelligenceProjectionMetricReason reason) {
        counter("engine_intelligence_projection_failure_total", "reason", projectionReason(reason)).increment();
    }

    public void recordProjectionLatency(Duration latency) {
        timer("engine_intelligence_projection_latency_seconds", latency);
    }

    public void recordProjectionDisposition(EngineIntelligenceProjectionDisposition disposition) {
        counter("engine_intelligence_projection_disposition_total", "disposition", disposition.name()).increment();
    }

    public void recordRecovery(EngineIntelligenceRecoveryOutcome outcome) {
        counter("engine_intelligence_recovery_total", "outcome", outcome.name()).increment();
    }

    public void recordPendingProjectionBacklog(long pendingCount, long unresolvedCount, long oldestAgeSeconds) {
        pendingProjection.set(Math.max(0L, pendingCount));
        unresolvedProjection.set(Math.max(0L, unresolvedCount));
        oldestPendingAgeSeconds.set(Math.max(0L, oldestAgeSeconds));
    }

    public void recordMlPredictionProjectionAttempt() {
        counter("ml_prediction_evidence_projection_attempt_total").increment();
    }

    public void recordMlPredictionProjectionSuccess() {
        counter("ml_prediction_evidence_projection_success_total").increment();
    }

    public void recordMlPredictionProjectionIdempotentReplay() {
        counter("ml_prediction_evidence_projection_idempotent_replay_total").increment();
    }

    public void recordMlPredictionProjectionOmitted(MlPredictionEvidenceProjectionMetricReason reason) {
        counter("ml_prediction_evidence_projection_omitted_total", "reason", mlProjectionReason(reason)).increment();
    }

    public void recordMlPredictionProjectionFailure(MlPredictionEvidenceProjectionMetricReason reason) {
        counter("ml_prediction_evidence_projection_failure_total", "reason", mlProjectionReason(reason)).increment();
    }

    public void recordMlPredictionProjectionLatency(Duration latency) {
        timer("ml_prediction_evidence_projection_latency_seconds", latency);
    }

    public void recordMlPredictionRecovery(String stage, String outcome) {
        counter(
                "ml_prediction_evidence_recovery_total",
                "stage", "REDRIVE".equals(stage) ? stage : "UNKNOWN",
                "outcome", recoveryOutcome(outcome)
        ).increment();
    }

    public void recordFeedbackSubmitAttempt() {
        counter("engine_intelligence_feedback_submit_attempt_total").increment();
    }

    public void recordFeedbackSubmitSuccess() {
        counter("engine_intelligence_feedback_submit_success_total").increment();
    }

    public void recordFeedbackSubmitValidationFailure() {
        counter("engine_intelligence_feedback_submit_validation_failure_total").increment();
    }

    public void recordFeedbackSubmitIdempotencyReplay() {
        counter("engine_intelligence_feedback_submit_idempotency_replay_total").increment();
    }

    public void recordFeedbackSubmitIdempotencyConflict() {
        counter("engine_intelligence_feedback_submit_idempotency_conflict_total").increment();
    }

    public void recordFeedbackSubmitAuditFailure() {
        counter("engine_intelligence_feedback_submit_audit_failure_total").increment();
    }

    public void recordFeedbackSubmitUnavailable(EngineIntelligenceFeedbackSubmitMetricReason reason) {
        String value = reason == null ? EngineIntelligenceFeedbackSubmitMetricReason.UNKNOWN_FAILURE.name() : reason.name();
        counter("engine_intelligence_feedback_submit_unavailable_total", "reason", value).increment();
    }

    public void recordFeedbackSubmitLatency(Duration latency) {
        timer("engine_intelligence_feedback_submit_latency_seconds", latency);
    }

    public void recordFeedbackReadAttempt() {
        counter("engine_intelligence_feedback_read_attempt_total").increment();
    }

    public void recordFeedbackReadSuccess() {
        counter("engine_intelligence_feedback_read_success_total").increment();
    }

    public void recordFeedbackReadEmpty() {
        counter("engine_intelligence_feedback_read_empty_total").increment();
    }

    public void recordFeedbackReadUnavailable(EngineIntelligenceFeedbackReadMetricReason reason) {
        String value = reason == null ? EngineIntelligenceFeedbackReadMetricReason.UNKNOWN_FAILURE.name() : reason.name();
        counter("engine_intelligence_feedback_read_unavailable_total", "reason", value).increment();
    }

    public void recordFeedbackReadValidationFailure() {
        counter("engine_intelligence_feedback_read_validation_failure_total").increment();
    }

    public void recordFeedbackReadAuditFailure() {
        counter("engine_intelligence_feedback_read_audit_failure_total").increment();
    }

    public void recordFeedbackReadLatency(Duration latency) {
        timer("engine_intelligence_feedback_read_latency_seconds", latency);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private void timer(String name, Duration latency) {
        Timer.builder(name).register(meterRegistry).record(nonNegative(latency));
    }

    private String projectionReason(EngineIntelligenceProjectionMetricReason reason) {
        return reason == null ? EngineIntelligenceProjectionMetricReason.UNKNOWN_FAILURE.name() : reason.name();
    }

    private String mlProjectionReason(MlPredictionEvidenceProjectionMetricReason reason) {
        return reason == null ? MlPredictionEvidenceProjectionMetricReason.UNKNOWN_FAILURE.name() : reason.name();
    }

    private String recoveryOutcome(String outcome) {
        return switch (outcome) {
            case "ATTEMPTED", "ACCEPTED", "TRANSIENT_FAILURE", "PERMANENT_FAILURE" -> outcome;
            default -> "UNKNOWN";
        };
    }

    private Duration nonNegative(Duration latency) {
        return latency == null || latency.isNegative() ? Duration.ZERO : latency;
    }
}
