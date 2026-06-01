package com.frauddetection.scoring.config;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.enabledContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineIntelligenceEnabledDiagnosticInvocationCountTest {

    @Test
    void enabledFlagRunsAdditionalRulesAndMlDiagnosticWorkOnce() {
        enabledContextRunner().run(context -> {
            RuleBasedFraudScoringEngine rules = context.getBean(RuleBasedFraudScoringEngine.class);
            MlFraudScoringEngine ml = context.getBean(MlFraudScoringEngine.class);
            when(rules.score(any())).thenReturn(result(0.15d, RiskLevel.LOW, Map.of()));
            when(ml.score(any())).thenReturn(result(0.91d, RiskLevel.CRITICAL, Map.of("modelAvailable", true)));

            var summary = context.getBean(EngineIntelligenceEmissionService.class)
                    .emitIfEnabled(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

            assertThat(summary).isPresent();
            verify(rules, times(1)).score(any());
            verify(ml, times(1)).score(any());
        });
    }

    private FraudScoreResult result(double score, RiskLevel riskLevel, Map<String, Object> explanationMetadata) {
        return new FraudScoreResult(
                score,
                riskLevel,
                "DIAGNOSTIC_TEST",
                "test-engine",
                "v1",
                Instant.parse("2026-05-31T10:00:00Z"),
                List.of(),
                Map.of(),
                Map.of(),
                explanationMetadata,
                false
        );
    }
}
