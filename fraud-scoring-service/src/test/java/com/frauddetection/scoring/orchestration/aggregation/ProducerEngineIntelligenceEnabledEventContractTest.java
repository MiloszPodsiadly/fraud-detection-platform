package com.frauddetection.scoring.orchestration.aggregation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerEngineIntelligenceEnabledEventContractTest {

    @Test
    void enabledFlagProducesSerializedEventWithValidBoundedEngineIntelligence() throws Exception {
        var summary = new PublicEngineIntelligenceMapper().map(
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy())
                        .aggregate(AggregationTestSupport.orchestration(
                                AggregationTestSupport.available("rules.primary", 0.8d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                                AggregationTestSupport.unavailable(
                                        "ml.python.primary",
                                        FraudEngineStatus.TIMEOUT,
                                        "ORCHESTRATOR_ENGINE_TIMEOUT"
                                )
                        ))
        );
        JsonNode intelligence = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build().valueToTree(
                new TransactionScoredEventMapper().toEvent(request(), scoreResult(), Optional.of(summary))
        ).path("engineIntelligence");

        assertThat(intelligence.isMissingNode()).isFalse();
        assertThat(intelligence.path("contractVersion").intValue()).isEqualTo(1);
        assertThat(intelligence.path("engines")).hasSizeLessThanOrEqualTo(2);
        assertThat(intelligence.path("diagnosticSignals")).hasSizeLessThanOrEqualTo(5);
        assertThat(intelligence.path("warnings")).hasSizeLessThanOrEqualTo(10);
        intelligence.path("engines").forEach(engine -> {
            assertThat(engine.path("reasonCodes")).hasSizeLessThanOrEqualTo(5);
            if (!engine.path("status").asText().equals("AVAILABLE")) {
                assertThat(engine.has("riskLevel")).isFalse();
            }
        });
        intelligence.path("diagnosticSignals").forEach(signal -> {
            if (signal.path("signalCategory").asText().equals("OPERATIONAL_SIGNAL")) {
                assertThat(signal.has("riskLevel")).isFalse();
                assertThat(signal.path("scoreBucket").asText()).isEqualTo("UNAVAILABLE");
            }
        });
        assertThat(intelligence.toString()).doesNotContainIgnoringCase(
                "rawScore",
                "evidence",
                "contribution",
                "featureVector",
                "endpoint",
                "token",
                "secret",
                "stackTrace",
                "finalDecision",
                "recommendedAction",
                "winningEngine",
                "FraudEngineAggregationResult",
                "NormalizedFraudEngineResult"
        );
    }

    private FraudScoringRequest request() {
        return FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());
    }

    private FraudScoreResult scoreResult() {
        return new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.parse("2026-05-31T10:00:00Z"),
                List.of("HIGH_VELOCITY"),
                Map.of(),
                Map.of(),
                Map.of(),
                true
        );
    }
}
