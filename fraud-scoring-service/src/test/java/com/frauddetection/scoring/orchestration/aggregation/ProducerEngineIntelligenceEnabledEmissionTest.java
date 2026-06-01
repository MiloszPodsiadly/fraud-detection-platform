package com.frauddetection.scoring.orchestration.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProducerEngineIntelligenceEnabledEmissionTest {

    @Test
    void explicitTrueEmitsBoundedPublicContract() throws Exception {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        when(orchestrator.evaluate(any())).thenReturn(AggregationTestSupport.orchestration(
                AggregationTestSupport.available("rules.primary", 0.8d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                AggregationTestSupport.unavailable(
                        "ml.python.primary",
                        FraudEngineStatus.TIMEOUT,
                        "ORCHESTRATOR_ENGINE_TIMEOUT"
                )
        ));
        var summary = new EngineIntelligenceEmissionService(
                new EngineIntelligenceEmissionProperties(true),
                new ScoringContextFactory(),
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                orchestrator,
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy()),
                new PublicEngineIntelligenceMapper()
        )
                .emitIfEnabled(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()))
                .orElseThrow();
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(summary);

        assertThat(summary.engines()).hasSize(2);
        assertThat(summary.engines().get(1).riskLevel()).isNull();
        assertThat(summary.engines().get(1).scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
        assertThat(json)
                .contains("\"contractVersion\":1", "\"status\":\"TIMEOUT\"")
                .doesNotContain("FraudEngineAggregationResult", "contributions", "evidence", "raw");
    }
}
