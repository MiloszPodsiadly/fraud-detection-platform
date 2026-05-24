package com.frauddetection.alert.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseReadModelMetricsContractTest {

    private static final Set<String> ALLOWED_OUTCOMES = Set.of(
            "available",
            "partial",
            "legacy",
            "truncated",
            "empty",
            "not_found",
            "error"
    );

    @Test
    void fraudCaseReadModelOutcomeLabelsAreExplicitAndAllowlisted() {
        assertThat(Arrays.stream(FraudCaseReadModelOutcome.values())
                .collect(Collectors.toMap(Enum::name, FraudCaseReadModelOutcome::label)))
                .containsEntry("AVAILABLE", "available")
                .containsEntry("PARTIAL", "partial")
                .containsEntry("LEGACY", "legacy")
                .containsEntry("TRUNCATED", "truncated")
                .containsEntry("EMPTY", "empty")
                .containsEntry("NOT_FOUND", "not_found")
                .containsEntry("ERROR", "error");

        assertThat(Arrays.stream(FraudCaseReadModelOutcome.values())
                .map(FraudCaseReadModelOutcome::label)
                .collect(Collectors.toSet()))
                .isEqualTo(ALLOWED_OUTCOMES);
    }

    @Test
    void fraudCaseReadModelMetricsAcceptOnlyBoundedOutcomeEnums() {
        for (Method method : FraudCaseReadModelMetrics.class.getDeclaredMethods()) {
            assertThat(method.getParameterTypes()).containsExactly(FraudCaseReadModelOutcome.class);
        }
    }

    @Test
    void fraudCaseReadModelMetricsUseBoundedEndpointAndOutcomeLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        for (FraudCaseReadModelOutcome outcome : FraudCaseReadModelOutcome.values()) {
            metrics.recordEvidenceSummary(outcome);
            metrics.recordEvidenceTimeline(outcome);
        }

        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getName().equals("fraud.fraud_case.read_model.read"))
                .hasSize(FraudCaseReadModelOutcome.values().length * 2)
                .allSatisfy(meter -> {
                    assertThat(meter.getId().getTags())
                            .extracting(Tag::getKey)
                            .containsExactlyInAnyOrder("endpoint", "outcome");
                    assertThat(meter.getId().getTag("endpoint"))
                            .isIn("evidence_summary", "evidence_timeline");
                    assertThat(meter.getId().getTag("outcome"))
                            .isIn(ALLOWED_OUTCOMES);
                });
    }

    @Test
    void fraudCaseReadModelMetricsDoNotLeakRawIdentifiersOrExceptions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);

        metrics.recordEvidenceSummary(FraudCaseReadModelOutcome.ERROR);
        metrics.recordEvidenceTimeline(FraudCaseReadModelOutcome.NOT_FOUND);

        String meterIds = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Meter.Id::toString)
                .collect(Collectors.joining("\n"));

        assertThat(meterIds)
                .doesNotContain("case-1")
                .doesNotContain("alert-1")
                .doesNotContain("customer-1")
                .doesNotContain("account-1")
                .doesNotContain("transaction-1")
                .doesNotContain("correlation-1")
                .doesNotContain("Exception")
                .doesNotContain("RuntimeException")
                .doesNotContain("path")
                .doesNotContain("query");
    }

    @Test
    void fraudCaseReadModelMetricSourceDoesNotUseRawRequestResponseOrExceptionData() throws Exception {
        String metricsInterface = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/observability/FraudCaseReadModelMetrics.java"
        ));
        String metricsImplementation = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/observability/AlertServiceMetrics.java"
        ));
        String recorderSlice = metricsImplementation.substring(
                metricsImplementation.indexOf("private void recordFraudCaseReadModel"),
                metricsImplementation.indexOf("private String endpoint", metricsImplementation.indexOf("private void recordFraudCaseReadModel"))
        );
        String source = metricsInterface + "\n" + recorderSlice;

        assertThat(metricsInterface)
                .doesNotContain("String")
                .doesNotContain("HttpServletRequest")
                .doesNotContain("Exception")
                .doesNotContain("Throwable")
                .doesNotContain("FraudCaseEvidenceSummaryResponse")
                .doesNotContain("FraudCaseEvidenceTimelineResponse");

        assertThat(source)
                .doesNotContain("getRequestURI")
                .doesNotContain("getQueryString")
                .doesNotContain("getParameterMap")
                .doesNotContain("exception.getMessage")
                .doesNotContain("getClass().getSimpleName")
                .doesNotContain("statusCode")
                .doesNotContain("principal");
    }
}
