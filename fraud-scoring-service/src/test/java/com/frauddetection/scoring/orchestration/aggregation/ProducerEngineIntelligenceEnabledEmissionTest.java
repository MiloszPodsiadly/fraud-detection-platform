package com.frauddetection.scoring.orchestration.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerEngineIntelligenceEnabledEmissionTest {

    @Test
    void explicitTrueEmitsBoundedPublicContract() throws Exception {
        var summary = new EngineIntelligenceEmissionService(new EngineIntelligenceEmissionProperties(true))
                .mapIfEnabled(this::aggregate)
                .orElseThrow();
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(summary);

        assertThat(summary.engines()).hasSize(2);
        assertThat(summary.engines().get(1).riskLevel()).isNull();
        assertThat(summary.engines().get(1).scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
        assertThat(json)
                .contains("\"contractVersion\":1", "\"status\":\"TIMEOUT\"")
                .doesNotContain("FraudEngineAggregationResult", "contributions", "evidence", "raw");
    }

    private FraudEngineAggregationResult aggregate() {
        return new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy())
                .aggregate(AggregationTestSupport.orchestration(
                        AggregationTestSupport.available("rules.primary", 0.8d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                        AggregationTestSupport.unavailable(
                                "ml.python.primary",
                                FraudEngineStatus.TIMEOUT,
                                "ORCHESTRATOR_ENGINE_TIMEOUT"
                        )
                ));
    }
}
