package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.flatten;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.throwingMlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.throwingRuleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.warningCodes;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorFailurePolicyTest {

    @Test
    void throwingEngineProducesBoundedFailureResult() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                throwingMlEngine(new IllegalStateException("secret token endpoint stacktrace"))
        ).evaluate(context());

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.engineResults().get(1).statusReason())
                .isEqualTo(OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION.wireValue());
        assertThat(flatten(result)).doesNotContain("secret", "token", "endpoint", "stacktrace");
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void throwingEngineDoesNotDropOtherEngineResults() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                throwingMlEngine(new IllegalStateException("secret token endpoint stacktrace"))
        ).evaluate(context());

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.engineResults()).extracting(engineResult -> engineResult.status())
                .containsExactly(FraudEngineStatus.AVAILABLE, FraudEngineStatus.DEGRADED);
        assertThat(result.engineResults().get(1).statusReason())
                .isEqualTo(OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION.wireValue());
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void nullEngineResultProducesBoundedDegradedResult() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(null)
        ).evaluate(context());

        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.engineResults().get(1).statusReason())
                .isEqualTo(OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_NULL_RESULT.wireValue());
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void requiredEngineFailureAddsBoundedWarning() {
        FraudScoringOrchestrationResult result = orchestrator(
                throwingRuleEngine(new IllegalStateException("secret token endpoint stacktrace")),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(warningCodes(result))
                .contains(
                        FraudScoringExecutionWarningCode.REQUIRED_ENGINE_NOT_AVAILABLE,
                        FraudScoringExecutionWarningCode.ENGINE_DEGRADED_RECORDED
                );
        assertThat(result.executionWarnings().getFirst().engineId()).isEqualTo("rules.primary");
        assertThat(result.executionWarnings().getFirst().required()).isTrue();
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
        assertNoDecisionFields();
    }

    @Test
    void optionalEngineFailureAddsBoundedWarning() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                throwingMlEngine(new IllegalStateException("secret token endpoint stacktrace"))
        ).evaluate(context());

        assertThat(warningCodes(result))
                .contains(
                        FraudScoringExecutionWarningCode.OPTIONAL_ENGINE_NOT_AVAILABLE,
                        FraudScoringExecutionWarningCode.ENGINE_DEGRADED_RECORDED
                );
        assertThat(result.executionWarnings().getFirst().engineId()).isEqualTo("ml.python.primary");
        assertThat(result.executionWarnings().getFirst().required()).isFalse();
        assertThat(result.engineResults().get(1).toString()).doesNotContain("LOW");
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertNoDecisionFields();
    }

    private FraudScoringOrchestrator orchestrator(FraudScoringOrchestratorTestSupport.FakeFraudSignalEngine... engines) {
        return new FraudScoringOrchestrator(new FraudSignalEngineRegistry(Arrays.asList(engines)));
    }

    private void assertNoDecisionFields() {
        assertThat(Arrays.stream(FraudScoringOrchestrationResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("finalDecision", "recommendedAction", "overallRisk", "finalRisk");
    }
}
