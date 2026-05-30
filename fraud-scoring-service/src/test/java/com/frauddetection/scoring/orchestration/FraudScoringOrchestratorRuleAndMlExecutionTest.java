package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.degradedResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.engineIds;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.flatten;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.timeoutResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.unavailableResult;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorRuleAndMlExecutionTest {

    @Test
    void orchestratorCollectsRuleAndMlResults() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.engineResults()).hasSize(2);
        assertThat(engineIds(result)).containsExactly("rules.primary", "ml.python.primary");
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.COMPLETE);
    }

    @Test
    void mlUnavailableDoesNotEraseRuleResult() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(unavailableResult(mlDescriptor()))
        ).evaluate(context());

        assertThat(result.engineResults()).extracting(engineResult -> engineResult.status())
                .containsExactly(FraudEngineStatus.AVAILABLE, FraudEngineStatus.UNAVAILABLE);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertNoDecisionFields();
    }

    @Test
    void mlTimeoutDoesNotBecomeLowRisk() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(timeoutResult(mlDescriptor()))
        ).evaluate(context());

        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.engineResults().get(1).riskLevel()).isNull();
        assertThat(result.engineResults().get(1).toString()).doesNotContain("LOW");
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void degradedEngineIsPreserved() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(degradedResult(mlDescriptor()))
        ).evaluate(context());

        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(flatten(result)).doesNotContain("raw", "diagnostic", "payload");
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void ruleAndMlScoresAreNotAggregated() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.20d, RiskLevel.LOW)),
                mlEngine(availableResult(mlDescriptor(), 0.91d, RiskLevel.HIGH))
        ).evaluate(context());

        assertThat(result.engineResults()).extracting(engineResult -> engineResult.score())
                .containsExactly(0.20d, 0.91d);
        assertNoDecisionFields();
    }

    private FraudScoringOrchestrator orchestrator(FraudScoringOrchestratorTestSupport.FakeFraudSignalEngine... engines) {
        return new FraudScoringOrchestrator(new FraudSignalEngineRegistry(Arrays.asList(engines)));
    }

    private void assertNoDecisionFields() {
        assertThat(Arrays.stream(FraudScoringOrchestrationResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("overallRisk", "finalRisk", "platformRisk", "finalDecision");
    }
}
