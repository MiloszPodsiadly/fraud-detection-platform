package com.frauddetection.scoring.config;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationResult;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.contextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineIntelligenceEmissionServiceSpringWiringTest {

    @Test
    void applicationContextContainsEngineIntelligenceEmissionProperties() {
        contextRunner().run(context ->
                assertThat(context).hasSingleBean(EngineIntelligenceEmissionProperties.class)
        );
    }

    @Test
    void applicationContextContainsEngineIntelligenceEmissionService() {
        contextRunner().run(context ->
                assertThat(context).hasSingleBean(EngineIntelligenceEmissionService.class)
        );
    }

    @Test
    void applicationContextBindsDefaultEmitEnabledFalse() {
        contextRunner().run(context ->
                assertThat(context.getBean(EngineIntelligenceEmissionProperties.class).emitEnabled()).isFalse()
        );
    }

    @Test
    void applicationContextBindsEnvOverrideTrue() {
        contextRunner()
                .withPropertyValues(EngineIntelligenceEmissionProperties.PROPERTY_NAME + "=true")
                .run(context ->
                        assertThat(context.getBean(EngineIntelligenceEmissionProperties.class).emitEnabled()).isTrue()
                );
    }

    @Test
    void serviceUsesInjectedPublicEngineIntelligenceMapper() {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregationService = mock(FraudEngineAggregationService.class);
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        FraudScoringOrchestrationResult orchestration = mock(FraudScoringOrchestrationResult.class);
        FraudEngineAggregationResult aggregation = mock(FraudEngineAggregationResult.class);
        EngineIntelligenceSummary summary = mock(EngineIntelligenceSummary.class);
        when(orchestrator.evaluate(any())).thenReturn(orchestration);
        when(aggregationService.aggregate(orchestration)).thenReturn(aggregation);
        when(mapper.map(aggregation)).thenReturn(summary);
        var service = new EngineIntelligenceEmissionService(
                new EngineIntelligenceEmissionProperties(true),
                new ScoringContextFactory(),
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                orchestrator,
                aggregationService,
                mapper
        );

        assertThat(service.emitIfEnabled(
                FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build())
        )).contains(summary);
        verify(mapper).map(aggregation);
    }
}
