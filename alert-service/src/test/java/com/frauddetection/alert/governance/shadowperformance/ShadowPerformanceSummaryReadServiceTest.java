package com.frauddetection.alert.governance.shadowperformance;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
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

    @TempDir
    Path tempDir;

    @Test
    void defaultProviderReturnsEmptyWhenNoCurrentSummaryConfigured() {
        assertThat(new EmptyShadowPerformanceSummaryProvider().currentSummary()).isEmpty();
    }

    @Test
    void returnsCurrentShadowPerformanceSummary() {
        ShadowPerformanceSummary summary = validSummary();
        when(provider.currentSummary()).thenReturn(Optional.of(summary));

        ShadowPerformanceSummaryResponse response = service.currentSummary();

        assertThat(response.reportType()).isEqualTo("SHADOW_PERFORMANCE_SUMMARY_V2");
        assertThat(response.evaluationPopulation().recordsEvaluated()).isEqualTo(5);
        assertThat(response.metrics().alertRecommendedPrecision().value()).isEqualTo(0.666667);
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
    void invalidSummaryReturnsServiceUnavailable() {
        ShadowPerformanceSummary summary = invalidSummary();
        when(provider.currentSummary()).thenReturn(Optional.of(summary));
        org.mockito.Mockito.doThrow(new ShadowPerformanceSummaryValidationException("invalid"))
                .when(validator).validate(summary);

        assertThatThrownBy(service::currentSummary)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(503);
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
    void configuredMissingArtifactMapsToServiceUnavailable() {
        Path missingArtifact = tempDir.resolve("current-summary.json");
        ShadowPerformanceSummaryProvider artifactProvider = new ArtifactBackedShadowPerformanceSummaryProvider(
                new ShadowPerformanceSummaryCurrentProperties(true, tempDir.toString(), missingArtifact.toString(), 1_048_576L),
                new ObjectMapper(),
                new ShadowPerformanceSummaryValidator()
        );
        ShadowPerformanceSummaryReadService artifactService = new ShadowPerformanceSummaryReadService(
                artifactProvider,
                new ShadowPerformanceSummaryValidator()
        );

        assertThatThrownBy(artifactService::currentSummary)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void mapsOnlyFromShadowPerformanceSummaryWithoutRecomputation() {
        ShadowPerformanceSummary summary = validSummary();
        ShadowPerformanceSummary changedMetrics = new ShadowPerformanceSummary(
                summary.reportType(),
                summary.summaryVersion(),
                summary.generatedAt(),
                summary.evaluationSubject(),
                summary.metricBasis(),
                summary.governance(),
                summary.evaluation(),
                summary.evaluationPopulation(),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(
                        ShadowPerformanceSummaryTestFixtures.metric(1.0),
                        ShadowPerformanceSummaryTestFixtures.metric(1.0),
                        ShadowPerformanceSummaryTestFixtures.metric(0.0),
                        summary.metrics().falseNegativeRate()
                ),
                summary.warnings(),
                summary.limitations(),
                summary.banner()
        );
        when(provider.currentSummary()).thenReturn(Optional.of(changedMetrics));

        ShadowPerformanceSummaryResponse response = service.currentSummary();

        assertThat(response.metrics().alertRecommendedPrecision().value()).isEqualTo(1.0);
        assertThat(response.metrics().alertRecommendedRecall().value()).isEqualTo(1.0);
        assertThat(response.metrics().falsePositiveRate().value()).isEqualTo(0.0);
    }

    private ShadowPerformanceSummary validSummary() {
        return ShadowPerformanceSummaryTestFixtures.validSummary();
    }

    private ShadowPerformanceSummary invalidSummary() {
        ShadowPerformanceSummary summary = validSummary();
        return new ShadowPerformanceSummary(
                summary.reportType(),
                summary.summaryVersion(),
                summary.generatedAt(),
                summary.evaluationSubject(),
                summary.metricBasis(),
                summary.governance(),
                summary.evaluation(),
                summary.evaluationPopulation(),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(
                        ShadowPerformanceSummaryTestFixtures.metric(2.0),
                        ShadowPerformanceSummaryTestFixtures.metric(0.5),
                        ShadowPerformanceSummaryTestFixtures.metric(0.25),
                        summary.metrics().falseNegativeRate()
                ),
                summary.warnings(),
                summary.limitations(),
                summary.banner()
        );
    }
}
