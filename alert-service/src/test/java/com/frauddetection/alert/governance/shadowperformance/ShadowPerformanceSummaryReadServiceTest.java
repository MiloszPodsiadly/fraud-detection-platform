package com.frauddetection.alert.governance.shadowperformance;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShadowPerformanceSummaryReadServiceTest {

    private final ShadowPerformanceSummaryProvider provider = mock(ShadowPerformanceSummaryProvider.class);
    private final ShadowPerformanceSummaryValidator validator = mock(ShadowPerformanceSummaryValidator.class);
    private final ShadowPerformanceSummaryReadService service = new ShadowPerformanceSummaryReadService(provider, validator);

    @Test
    void returnsCurrentShadowPerformanceSummary() {
        ShadowPerformanceSummary summary = validSummary();
        when(provider.currentSummary()).thenReturn(Optional.of(summary));

        ShadowPerformanceSummaryResponse response = service.currentSummary();

        assertThat(response.summaryType()).isEqualTo("SHADOW_PERFORMANCE_SUMMARY_V1");
        assertThat(response.evaluationPopulation().datasetRecordsRead()).isEqualTo(5);
        assertThat(response.metrics().precisionAtBudget()).isEqualTo(0.666667);
        verify(validator).validate(summary);
    }

    @Test
    void returns404WhenSummaryDoesNotExist() {
        when(provider.currentSummary()).thenReturn(Optional.empty());

        assertThatThrownBy(service::currentSummary)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(404);

        verifyNoInteractions(validator);
    }

    @Test
    void returns422WhenSummaryInvalid() {
        ShadowPerformanceSummary summary = invalidSummary();
        when(provider.currentSummary()).thenReturn(Optional.of(summary));
        org.mockito.Mockito.doThrow(new ShadowPerformanceSummaryValidationException("invalid"))
                .when(validator).validate(summary);

        assertThatThrownBy(service::currentSummary)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(422);
    }

    @Test
    void returns503WhenSummaryProviderUnavailable() {
        when(provider.currentSummary()).thenThrow(new ShadowPerformanceSummaryProviderUnavailableException("store unavailable"));

        assertThatThrownBy(service::currentSummary)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(503);

        verifyNoInteractions(validator);
    }

    @Test
    void mapsOnlyFromShadowPerformanceSummaryWithoutRecomputation() {
        ShadowPerformanceSummary summary = validSummary();
        ShadowPerformanceSummary changedMetrics = new ShadowPerformanceSummary(
                summary.summaryType(),
                summary.summaryVersion(),
                summary.generatedAt(),
                summary.model(),
                summary.governance(),
                summary.evaluation(),
                summary.evaluationPopulation(),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(1.0, 1.0, 0.0, 1, 1, 1, 1, 1, 1),
                summary.disagreementSummary(),
                summary.warnings(),
                summary.limitations(),
                summary.banner()
        );
        when(provider.currentSummary()).thenReturn(Optional.of(changedMetrics));

        ShadowPerformanceSummaryResponse response = service.currentSummary();

        assertThat(response.metrics().precisionAtBudget()).isEqualTo(1.0);
        assertThat(response.metrics().recallAtTopK()).isEqualTo(1.0);
        assertThat(response.metrics().falsePositiveRate()).isEqualTo(0.0);
    }

    private ShadowPerformanceSummary validSummary() {
        return new StaticShadowPerformanceSummaryProvider().currentSummary().orElseThrow();
    }

    private ShadowPerformanceSummary invalidSummary() {
        ShadowPerformanceSummary summary = validSummary();
        return new ShadowPerformanceSummary(
                summary.summaryType(),
                summary.summaryVersion(),
                summary.generatedAt(),
                summary.model(),
                summary.governance(),
                summary.evaluation(),
                summary.evaluationPopulation(),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(2.0, 0.5, 0.25, 1, 1, 1, 1, 1, 1),
                summary.disagreementSummary(),
                summary.warnings(),
                summary.limitations(),
                summary.banner()
        );
    }
}
