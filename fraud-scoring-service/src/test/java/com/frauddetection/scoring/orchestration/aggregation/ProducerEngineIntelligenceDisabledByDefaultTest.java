package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProducerEngineIntelligenceDisabledByDefaultTest {

    @Test
    void defaultConfigDoesNotEmitEngineIntelligence() {
        assertDisabled(new EngineIntelligenceEmissionProperties(false));
    }

    @Test
    void explicitFalseDoesNotEmitEngineIntelligence() {
        assertDisabled(new EngineIntelligenceEmissionProperties(false));
    }

    @Test
    void disabledFlagDoesNotInvokePublicMapperOrAggregationSupplier() {
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregationService = mock(FraudEngineAggregationService.class);
        var service = service(new EngineIntelligenceEmissionProperties(false), orchestrator, aggregationService, mapper);

        assertThat(service.emitIfEnabled(request())).isEmpty();
        verifyNoInteractions(orchestrator, aggregationService, mapper);
    }

    private void assertDisabled(EngineIntelligenceEmissionProperties properties) {
        assertThat(service(
                properties,
                mock(FraudScoringOrchestrator.class),
                mock(FraudEngineAggregationService.class),
                mock(PublicEngineIntelligenceMapper.class)
        ).emitIfEnabled(request())).isEmpty();
    }

    private EngineIntelligenceEmissionService service(
            EngineIntelligenceEmissionProperties properties,
            FraudScoringOrchestrator orchestrator,
            FraudEngineAggregationService aggregationService,
            PublicEngineIntelligenceMapper mapper
    ) {
        return new EngineIntelligenceEmissionService(
                properties,
                new ScoringContextFactory(),
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                orchestrator,
                aggregationService,
                mapper
        );
    }

    private FraudScoringRequest request() {
        return FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());
    }
}
