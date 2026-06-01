package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.pipeline;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.request;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProducerEngineIntelligenceDisabledByDefaultTest {

    @Test
    void defaultConfigDoesNotEmitEngineIntelligence() {
        assertDisabled();
    }

    @Test
    void explicitFalseDoesNotEmitEngineIntelligence() {
        assertDisabled();
    }

    @Test
    void disabledFlagDoesNotInvokePublicMapperOrAggregationSupplier() {
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregationService = mock(FraudEngineAggregationService.class);
        var service = service(false, pipeline(orchestrator, aggregationService, mapper));

        assertThat(service.emitIfEnabled(request())).isEmpty();
        verifyNoInteractions(orchestrator, aggregationService, mapper);
    }

    private void assertDisabled() {
        assertThat(service(false, mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class))
                .emitIfEnabled(request())).isEmpty();
    }
}
