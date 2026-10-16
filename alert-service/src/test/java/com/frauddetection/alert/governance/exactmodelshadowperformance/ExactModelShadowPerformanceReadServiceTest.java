package com.frauddetection.alert.governance.exactmodelshadowperformance;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExactModelShadowPerformanceReadServiceTest {

    private static final String METRIC = "governance_exact_model_shadow_performance_read_total";

    private final ExactModelShadowPerformanceProvider provider = mock(ExactModelShadowPerformanceProvider.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ExactModelShadowPerformanceReadService service = new ExactModelShadowPerformanceReadService(
            provider, new ExactModelShadowPerformanceReadMetrics(meterRegistry));

    @Test
    void returnsExactArtifactAndRecordsSuccess() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector();
        var summary = new ObjectMapper().readTree("{\"schemaVersion\":\"exact-model-shadow-performance-v1\"}");
        when(provider.find(selector)).thenReturn(Optional.of(summary));

        assertThat(service.find(selector)).isSameAs(summary);
        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    void missingExactArtifactReturnsNotFoundAndRecordsOutcome() {
        ExactModelShadowPerformanceSelector selector = selector();
        when(provider.find(selector)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(selector))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(404);
        assertThat(counter("not_found")).isEqualTo(1.0);
    }

    @Test
    void unavailableExactArtifactReturnsServiceUnavailableAndRecordsOutcome() {
        ExactModelShadowPerformanceSelector selector = selector();
        when(provider.find(selector)).thenThrow(new ExactModelShadowPerformanceUnavailableException("unavailable"));

        assertThatThrownBy(() -> service.find(selector))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(503);
        assertThat(counter("unavailable")).isEqualTo(1.0);
    }

    private double counter(String outcome) {
        return meterRegistry.get(METRIC).tag("outcome", outcome).counter().count();
    }

    private ExactModelShadowPerformanceSelector selector() {
        return new ExactModelShadowPerformanceSelector(
                "python-logistic-fraud-model",
                "2026-10-15.shadow",
                "feature-contract-v2",
                "a".repeat(64),
                "2026-05-01T00:00:00Z",
                "2026-06-01T00:00:00Z"
        );
    }
}
