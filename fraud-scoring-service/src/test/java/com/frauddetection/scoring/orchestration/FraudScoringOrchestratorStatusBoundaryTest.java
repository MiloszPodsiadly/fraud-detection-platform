package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.degradedResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.engineIds;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.unavailableResult;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorStatusBoundaryTest {

    @Test
    void completeStatusDoesNotMeanSafeOrApproved() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.COMPLETE);
        assertNoDecisionSurface();
        assertThat(result.toString()).doesNotContain("safe", "approved");
    }

    @Test
    void partialStatusDoesNotMeanLowRisk() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                mlEngine(unavailableResult(mlDescriptor()))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(result.engineResults().get(1).riskLevel()).isNull();
        assertThat(result.engineResults().get(1).toString()).doesNotContain("LOW");
        assertNoDecisionSurface();
    }

    @Test
    void requiredEngineFailedDoesNotMeanDeclineOrBlock() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(degradedResult(ruleDescriptor())),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
        assertThat(result.engineResults()).hasSize(2);
        assertThat(engineIds(result)).containsExactly("rules.primary", "ml.python.primary");
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertNoDecisionSurface();
    }

    @Test
    void orchestrationStatusIsNotExposedByTransactionScoredEvent() {
        assertThat(recordComponentNames(TransactionScoredEvent.class))
                .doesNotContain(
                        "orchestrationStatus",
                        "engineResults",
                        "finalDecisionSource",
                        "recommendedAnalystAction"
                );
    }

    private FraudScoringOrchestrator orchestrator(FraudScoringOrchestratorTestSupport.FakeFraudSignalEngine... engines) {
        return new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(engines)));
    }

    private void assertNoDecisionSurface() {
        assertThat(recordComponentNames(FraudScoringOrchestrationResult.class))
                .doesNotContain("safe", "approve", "approved", "decline", "block", "finalDecision", "finalRisk", "recommendedAction");
        assertThat(methodNames(FraudScoringOrchestrationResult.class))
                .doesNotContain("safe", "approve", "approved", "decline", "block", "finalDecision", "finalRisk", "recommendedAction");
        assertThat(methodNames(FraudScoringOrchestrator.class))
                .doesNotContain("safe", "approve", "approved", "decline", "block", "recommend", "finalizeDecision");
    }

    private String[] recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }

    private String[] methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(Method::getName)
                .toArray(String[]::new);
    }
}
