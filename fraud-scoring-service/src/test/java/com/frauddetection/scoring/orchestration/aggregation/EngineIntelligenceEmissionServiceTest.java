package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.pipeline;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.provider;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.request;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EngineIntelligenceEmissionServiceTest {

    @Test
    void emitDisabledReturnsEmpty() {
        assertThat(service(false, mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class))
                .emitIfEnabled(request())).isEmpty();
    }

    @Test
    void emitDisabledDoesNotResolveProviderOrInvokeDiagnosticPipeline() {
        ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> provider = provider(
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class)
        );
        var service = new EngineIntelligenceEmissionService(
                new com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties(false),
                provider
        );

        assertThat(service.emitIfEnabled(request())).isEmpty();
        verifyNoInteractions(provider);
    }

    @Test
    void emitDisabledDoesNotInvokeOrchestratorAggregationOrPublicMapper() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregation = mock(FraudEngineAggregationService.class);
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);

        assertThat(service(false, pipeline(orchestrator, aggregation, mapper)).emitIfEnabled(request())).isEmpty();
        verifyNoInteractions(orchestrator, aggregation, mapper);
    }

    @Test
    void emitEnabledInvokesOrchestratorAggregationAndMapperAndReturnsPublicSummary() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregation = mock(FraudEngineAggregationService.class);
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        FraudScoringOrchestrationResult orchestration = mock(FraudScoringOrchestrationResult.class);
        FraudEngineAggregationResult aggregationResult = mock(FraudEngineAggregationResult.class);
        EngineIntelligenceSummary summary = mock(EngineIntelligenceSummary.class);
        when(orchestrator.evaluate(any())).thenReturn(orchestration);
        when(aggregation.aggregate(orchestration)).thenReturn(aggregationResult);
        when(mapper.map(aggregationResult)).thenReturn(summary);

        assertThat(service(true, pipeline(orchestrator, aggregation, mapper)).emitIfEnabled(request()))
                .contains(summary);
        verify(orchestrator).evaluate(any());
        verify(aggregation).aggregate(orchestration);
        verify(mapper).map(aggregationResult);
    }

    @Test
    void emitEnabledResolvesProviderOnce() {
        EngineIntelligenceDiagnosticEnrichmentPipeline pipeline =
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
        ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> provider = provider(pipeline);
        when(pipeline.enrich(any())).thenReturn(Optional.empty());
        var service = new EngineIntelligenceEmissionService(
                new com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties(true),
                provider
        );

        assertThat(service.emitIfEnabled(request())).isEmpty();
        verify(provider).getIfAvailable();
    }

    @Test
    void emitEnabledWhenOrchestratorThrowsReturnsEmptyBeforeAggregationOrMapping() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregation = mock(FraudEngineAggregationService.class);
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        when(orchestrator.evaluate(any())).thenThrow(new IllegalStateException("raw-secret"));

        assertThat(service(true, pipeline(orchestrator, aggregation, mapper)).emitIfEnabled(request())).isEmpty();
        verifyNoInteractions(aggregation, mapper);
    }

    @Test
    void emitEnabledWhenAggregationThrowsReturnsEmptyBeforeMapping() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregation = mock(FraudEngineAggregationService.class);
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        when(orchestrator.evaluate(any())).thenReturn(mock(FraudScoringOrchestrationResult.class));
        when(aggregation.aggregate(any())).thenThrow(new IllegalStateException("raw-secret"));

        assertThat(service(true, pipeline(orchestrator, aggregation, mapper)).emitIfEnabled(request())).isEmpty();
        verify(mapper, never()).map(any());
    }

    @Test
    void emitEnabledWhenMapperThrowsReturnsEmpty() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregation = mock(FraudEngineAggregationService.class);
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        when(orchestrator.evaluate(any())).thenReturn(mock(FraudScoringOrchestrationResult.class));
        when(aggregation.aggregate(any())).thenReturn(mock(FraudEngineAggregationResult.class));
        when(mapper.map(any())).thenThrow(new IllegalStateException("raw-secret"));

        assertThat(service(true, pipeline(orchestrator, aggregation, mapper)).emitIfEnabled(request())).isEmpty();
    }

    @Test
    void emitEnabledWhenPipelineIsMissingReturnsEmpty() {
        assertThat(service(true, null).emitIfEnabled(request())).isEmpty();
    }

    @Test
    void nullScoringRequestThrowsBeforeSwallowing() {
        assertThatThrownBy(() -> service(false, mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class))
                .emitIfEnabled(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("scoringRequest is required");
    }

    @Test
    void failureLogDoesNotContainRawExceptionMessage() throws Exception {
        String source = Files.readString(moduleRoot().resolve(
                "src/main/java/com/frauddetection/scoring/orchestration/aggregation/EngineIntelligenceEmissionService.java"
        ));

        assertThat(source)
                .contains("log.warn(\"Engine intelligence enrichment omitted.\")")
                .doesNotContain("exception.getMessage()", "log.warn(\"Engine intelligence enrichment omitted.\", exception)");
    }

    private Path moduleRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("src/main")) ? current : current.resolve("fraud-scoring-service");
    }
}
