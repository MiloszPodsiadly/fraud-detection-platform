package com.frauddetection.alert.suspicious.api.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class LinkedAlertContextMetricsRecorderTest {

    private static final Set<String> ALLOWED_LABELS = Set.of(
            "available",
            "no_linked_alert",
            "linked_alert_not_found",
            "relationship_mismatch",
            "temporarily_unavailable",
            "validation_error",
            "suspicious_transaction_not_found",
            "error"
    );
    private static final Set<String> FORBIDDEN_VALUES = Set.of(
            "suspicious-secret-123",
            "alert-secret-456",
            "customer-secret-789",
            "account-secret-999",
            "txn-secret-777",
            "corr-secret-555",
            "score-secret-333",
            "/internal/suspicious-transactions/suspicious-secret-123/linked-alert",
            "alert-secret-456 failed for customer-secret-789"
    );

    @Test
    void linkedAlertContextMetricOutcomeLabelsAreExplicit() {
        assertThat(Arrays.stream(LinkedAlertContextMetricOutcome.values())
                .collect(Collectors.toMap(Enum::name, LinkedAlertContextMetricOutcome::label)))
                .containsEntry("AVAILABLE", "available")
                .containsEntry("NO_LINKED_ALERT", "no_linked_alert")
                .containsEntry("LINKED_ALERT_NOT_FOUND", "linked_alert_not_found")
                .containsEntry("RELATIONSHIP_MISMATCH", "relationship_mismatch")
                .containsEntry("TEMPORARILY_UNAVAILABLE", "temporarily_unavailable")
                .containsEntry("VALIDATION_ERROR", "validation_error")
                .containsEntry("SUSPICIOUS_TRANSACTION_NOT_FOUND", "suspicious_transaction_not_found")
                .containsEntry("ERROR", "error");
    }

    @Test
    void linkedAlertContextMetricOutcomeLabelsAreAllowlisted() {
        assertThat(Arrays.stream(LinkedAlertContextMetricOutcome.values())
                .map(LinkedAlertContextMetricOutcome::label)
                .collect(Collectors.toSet()))
                .isEqualTo(ALLOWED_LABELS);
    }

    @Test
    void linkedAlertContextMetricOutcomeDoesNotUseDynamicStrings() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/suspicious/api/observability/LinkedAlertContextMetricOutcome.java"
        ));

        assertThat(source)
                .doesNotContain(".name().toLowerCase")
                .doesNotContain("getSimpleName")
                .doesNotContain("getMessage");
    }

    @Test
    void validationAndNotFoundOutcomesAreDocumentedAsBoundedEndpointOutcomes() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/suspicious/api/observability/LinkedAlertContextMetricOutcome.java"
        ));

        assertThat(source)
                .contains("unsupported selector such as alertId")
                .contains("bounded endpoint outcome, not raw validation detail")
                .contains("Source SuspiciousTransaction was not found")
                .contains("bounded endpoint outcome, not a raw identifier");
    }

    @Test
    void linkedAlertContextMetricsRecorderAcceptsBoundedOutcomeOnly() {
        Method record = Arrays.stream(LinkedAlertContextMetricsRecorder.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("record"))
                .findFirst()
                .orElseThrow();

        assertThat(record.getParameterTypes()).containsExactly(LinkedAlertContextMetricOutcome.class);
    }

    @Test
    void linkedAlertContextMetricsRecorderDoesNotAcceptRequestEntityResponseOrException() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/suspicious/api/observability/LinkedAlertContextMetricsRecorder.java"
        ));

        assertThat(source)
                .doesNotContain("HttpServletRequest")
                .doesNotContain("AlertLinkedContextResponse")
                .doesNotContain("AlertDocument")
                .doesNotContain("SuspiciousTransactionDocument")
                .doesNotContain("Exception")
                .doesNotContain("Throwable");
    }

    @Test
    void linkedAlertContextMetricsRecorderDoesNotAcceptRawIdentifiers() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/suspicious/api/observability/LinkedAlertContextMetricsRecorder.java"
        ));

        assertThat(source)
                .doesNotContain("suspiciousTransactionId")
                .doesNotContain("alertId")
                .doesNotContain("linkedAlertId")
                .doesNotContain("customerId")
                .doesNotContain("accountId")
                .doesNotContain("transactionId")
                .doesNotContain("correlationId")
                .doesNotContain("scoreDecisionId")
                .doesNotContain("String");
    }

    @Test
    void linkedAlertContextMetricsUseBoundedEndpointAndOutcomeLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLinkedAlertContextMetricsRecorder recorder = new MicrometerLinkedAlertContextMetricsRecorder(registry);

        for (LinkedAlertContextMetricOutcome outcome : LinkedAlertContextMetricOutcome.values()) {
            recorder.record(outcome);
        }

        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getName().equals(MicrometerLinkedAlertContextMetricsRecorder.METRIC_NAME))
                .hasSize(LinkedAlertContextMetricOutcome.values().length)
                .allSatisfy(meter -> {
                    assertThat(meter.getId().getTags())
                            .extracting(Tag::getKey)
                            .containsExactlyInAnyOrder("endpoint", "outcome");
                    assertThat(meter.getId().getTag("endpoint"))
                            .isEqualTo(MicrometerLinkedAlertContextMetricsRecorder.ENDPOINT_LABEL);
                    assertThat(meter.getId().getTag("outcome"))
                            .isIn(ALLOWED_LABELS);
                });
    }

    @Test
    void linkedAlertContextMetricsNoRawIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLinkedAlertContextMetricsRecorder recorder = new MicrometerLinkedAlertContextMetricsRecorder(registry);

        recorder.record(LinkedAlertContextMetricOutcome.ERROR);

        String meterIds = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Meter.Id::toString)
                .collect(Collectors.joining("\n"));

        assertThat(meterIds).doesNotContain(FORBIDDEN_VALUES.toArray(String[]::new));
    }

    @Test
    void linkedAlertContextMetricsNoRawExceptionMessage(CapturedOutput output) {
        MicrometerLinkedAlertContextMetricsRecorder recorder =
                new MicrometerLinkedAlertContextMetricsRecorder(mock(MeterRegistry.class));

        recorder.record(LinkedAlertContextMetricOutcome.ERROR);

        assertThat(output)
                .contains("Linked alert context metric recording failed outcome=error")
                .doesNotContain(FORBIDDEN_VALUES.toArray(String[]::new))
                .doesNotContain("NullPointerException")
                .doesNotContain("IllegalStateException");
    }

    @Test
    void linkedAlertContextMetricSourceDoesNotUseRawRequestOrExceptionData() throws Exception {
        String recorder = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/suspicious/api/observability/MicrometerLinkedAlertContextMetricsRecorder.java"
        ));
        String controller = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/suspicious/api/SuspiciousTransactionReadController.java"
        ));
        String source = recorder + "\n" + controller;

        assertThat(source)
                .doesNotContain("getRequestURI")
                .doesNotContain("getQueryString")
                .doesNotContain("getParameterMap")
                .doesNotContain("exception.getMessage")
                .doesNotContain("getClass().getSimpleName")
                .doesNotContain("exceptionType")
                .doesNotContain("statusCode")
                .doesNotContain("authState");
    }
}
