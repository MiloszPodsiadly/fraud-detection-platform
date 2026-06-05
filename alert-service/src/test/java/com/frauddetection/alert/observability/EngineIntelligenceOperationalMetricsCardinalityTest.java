package com.frauddetection.alert.observability;

import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackReadMetricReason;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackSubmitMetricReason;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceProjectionMetricReason;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceOperationalMetricsCardinalityTest {

    private static final Set<String> ALLOWED_REASON_VALUES = Set.of(
            "ENGINE_INTELLIGENCE_ABSENT",
            "INVALID_PROJECTION_SHAPE",
            "STORE_UNAVAILABLE",
            "VALIDATION_FAILED",
            "UNKNOWN_FAILURE",
            "IDEMPOTENCY_REPLAY",
            "IDEMPOTENCY_CONFLICT",
            "AUDIT_FAILURE",
            "EMPTY_RESULT",
            "CORRUPTED_STORED_FEEDBACK"
    );

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

    @Test
    void metricsDoNotAcceptTransactionIdAsLabel() {
        assertNoForbiddenMetricText("transactionId", "txn-raw-123");
    }

    @Test
    void metricsDoNotAcceptFeedbackIdAsLabel() {
        assertNoForbiddenMetricText("feedbackId", "feedback-raw-123");
    }

    @Test
    void metricsDoNotAcceptSubmittedByAsLabel() {
        assertNoForbiddenMetricText("submittedBy", "analyst-raw-123");
    }

    @Test
    void metricsDoNotAcceptCustomerIdAsLabel() {
        assertNoForbiddenMetricText("customerId", "customer-raw-123");
    }

    @Test
    void metricsDoNotAcceptAccountIdAsLabel() {
        assertNoForbiddenMetricText("accountId", "account-raw-123");
    }

    @Test
    void metricsDoNotAcceptCardIdAsLabel() {
        assertNoForbiddenMetricText("cardId", "card-raw-123");
    }

    @Test
    void metricsDoNotAcceptMerchantIdAsLabel() {
        assertNoForbiddenMetricText("merchantId", "merchant-raw-123");
    }

    @Test
    void metricsDoNotAcceptCorrelationIdAsLabel() {
        assertNoForbiddenMetricText("correlationId", "corr-raw-123");
    }

    @Test
    void metricsDoNotAcceptRawEndpointPathAsLabel() {
        assertNoForbiddenMetricText("endpoint", "/api/v1/transactions/scored/txn-raw/engine-intelligence/feedback");
    }

    @Test
    void metricsDoNotAcceptRawExceptionAsLabel() {
        assertNoForbiddenMetricText("exception", "IllegalStateException raw token secret stacktrace");
    }

    @Test
    void metricsDoNotAcceptRawReasonCodeAsLabel() {
        assertNoForbiddenMetricText("reasonCode", "raw reason token secret stacktrace");
    }

    @Test
    void metricsDoNotAcceptIdempotencyKeyOrHashAsLabel() {
        assertNoForbiddenMetricText("idempotencyKey", "feedback-key-raw");
        assertNoForbiddenMetricText("idempotencyKeyHash", "hash-raw");
    }

    @Test
    void metricsDoNotAcceptRequestPayloadHashAsLabel() {
        assertNoForbiddenMetricText("requestPayloadHash", "payload-hash-raw");
    }

    @Test
    void metricsUseOnlyBoundedReasonLabels() {
        recordAllMetrics();

        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getTags().stream().anyMatch(tag -> tag.getKey().equals("reason")))
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .filteredOn(tag -> tag.getKey().equals("reason"))
                        .extracting(Tag::getValue)
                        .allSatisfy(value -> assertThat(value).isIn(ALLOWED_REASON_VALUES)));
    }

    @Test
    void metricsUseOnlyBoundedOperationLabels() {
        recordAllMetrics();

        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags().stream().map(Tag::getKey).toList())
                .doesNotContain("operation")
                .doesNotContain("surface");
    }

    @Test
    void metricsUseOnlyBoundedOutcomeLabels() {
        recordAllMetrics();

        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags().stream().map(Tag::getKey).toList())
                .doesNotContain("outcome");
    }

    @Test
    void metricsReasonsAreEnumsOnly() {
        Set<Class<?>> reasonParameterTypes = Arrays.stream(AlertServiceMetrics.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("recordEngineIntelligence"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .filter(type -> type.getSimpleName().endsWith("MetricReason"))
                .collect(Collectors.toSet());

        assertThat(reasonParameterTypes).containsExactlyInAnyOrder(
                EngineIntelligenceProjectionMetricReason.class,
                EngineIntelligenceFeedbackSubmitMetricReason.class,
                EngineIntelligenceFeedbackReadMetricReason.class
        );
        assertThat(Arrays.stream(AlertServiceMetrics.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("recordEngineIntelligence"))
                .map(Method::getParameterTypes)
                .flatMap(Arrays::stream))
                .doesNotContain(String.class, Throwable.class, Exception.class);
    }

    @Test
    void unknownReasonFallsBackToUnknownFailure() {
        metrics.recordEngineIntelligenceProjectionFailure(null);
        metrics.recordEngineIntelligenceFeedbackSubmitUnavailable(null);
        metrics.recordEngineIntelligenceFeedbackReadUnavailable(null);

        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getTags().stream()
                        .anyMatch(tag -> tag.getKey().equals("reason")))
                .allSatisfy(meter -> assertThat(meter.getId().getTag("reason")).isEqualTo("UNKNOWN_FAILURE"));
    }

    @Test
    void rawExceptionMessageCannotBecomeMetricReason() {
        recordAllMetrics();

        assertThat(meterIds())
                .doesNotContain("IllegalStateException")
                .doesNotContain("raw token secret stacktrace")
                .doesNotContain("exception");
    }

    @Test
    void metricsLatencyRejectsNegativeDurationOrNormalizesSafely() {
        metrics.recordEngineIntelligenceProjectionLatency(Duration.ofMillis(-1));
        metrics.recordEngineIntelligenceFeedbackSubmitLatency(Duration.ofMillis(-1));
        metrics.recordEngineIntelligenceFeedbackReadLatency(Duration.ofMillis(-1));

        assertThat(registry.get("engine_intelligence_projection_latency_seconds").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isZero();
        assertThat(registry.get("engine_intelligence_feedback_submit_latency_seconds").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isZero();
        assertThat(registry.get("engine_intelligence_feedback_read_latency_seconds").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isZero();
    }

    private void assertNoForbiddenMetricText(String forbiddenKey, String forbiddenValue) {
        recordAllMetrics();

        assertThat(Arrays.stream(AlertServiceMetrics.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("recordEngineIntelligence"))
                .map(Method::getParameterTypes)
                .flatMap(Arrays::stream))
                .doesNotContain(String.class);
        assertThat(meterIds())
                .doesNotContain(forbiddenKey)
                .doesNotContain(forbiddenValue);
    }

    private void recordAllMetrics() {
        metrics.recordEngineIntelligenceProjectionAttempt();
        metrics.recordEngineIntelligenceProjectionSuccess();
        metrics.recordEngineIntelligenceProjectionOmitted(EngineIntelligenceProjectionMetricReason.ENGINE_INTELLIGENCE_ABSENT);
        metrics.recordEngineIntelligenceProjectionFailure(EngineIntelligenceProjectionMetricReason.STORE_UNAVAILABLE);
        metrics.recordEngineIntelligenceProjectionLatency(Duration.ofMillis(1));
        metrics.recordEngineIntelligenceFeedbackSubmitAttempt();
        metrics.recordEngineIntelligenceFeedbackSubmitSuccess();
        metrics.recordEngineIntelligenceFeedbackSubmitValidationFailure();
        metrics.recordEngineIntelligenceFeedbackSubmitIdempotencyReplay();
        metrics.recordEngineIntelligenceFeedbackSubmitIdempotencyConflict();
        metrics.recordEngineIntelligenceFeedbackSubmitAuditFailure();
        metrics.recordEngineIntelligenceFeedbackSubmitUnavailable(EngineIntelligenceFeedbackSubmitMetricReason.AUDIT_FAILURE);
        metrics.recordEngineIntelligenceFeedbackSubmitLatency(Duration.ofMillis(1));
        metrics.recordEngineIntelligenceFeedbackReadAttempt();
        metrics.recordEngineIntelligenceFeedbackReadSuccess();
        metrics.recordEngineIntelligenceFeedbackReadEmpty();
        metrics.recordEngineIntelligenceFeedbackReadUnavailable(EngineIntelligenceFeedbackReadMetricReason.CORRUPTED_STORED_FEEDBACK);
        metrics.recordEngineIntelligenceFeedbackReadValidationFailure();
        metrics.recordEngineIntelligenceFeedbackReadAuditFailure();
        metrics.recordEngineIntelligenceFeedbackReadLatency(Duration.ofMillis(1));
    }

    private String meterIds() {
        return registry.getMeters().stream()
                .map(Meter::getId)
                .map(Meter.Id::toString)
                .collect(Collectors.joining("\n"));
    }
}
