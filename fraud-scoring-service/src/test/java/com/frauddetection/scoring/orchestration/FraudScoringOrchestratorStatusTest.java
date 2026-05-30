package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.degradedResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.throwingRuleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.timeoutResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.unavailableResult;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorStatusTest {

    @Test
    void allEnginesAvailableProducesComplete() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.COMPLETE);
    }

    @Test
    void optionalMlUnavailableProducesPartial() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(unavailableResult(mlDescriptor()))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void optionalMlTimeoutProducesPartial() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(timeoutResult(mlDescriptor()))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void optionalMlDegradedProducesPartial() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                mlEngine(degradedResult(mlDescriptor()))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void requiredRulesUnavailableProducesRequiredEngineFailed() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(unavailableResult(ruleDescriptor())),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
    }

    @Test
    void requiredRulesTimeoutProducesRequiredEngineFailed() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(timeoutResult(ruleDescriptor())),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
    }

    @Test
    void requiredRulesDegradedProducesRequiredEngineFailed() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(degradedResult(ruleDescriptor())),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
    }

    @Test
    void requiredRulesThrowsProducesRequiredEngineFailed() {
        FraudScoringOrchestrationResult result = orchestrator(
                throwingRuleEngine(new IllegalStateException("secret token endpoint stacktrace")),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
    }

    @Test
    void requiredRulesNullResultProducesRequiredEngineFailed() {
        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(null),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
    }

    private FraudScoringOrchestrator orchestrator(FraudScoringOrchestratorTestSupport.FakeFraudSignalEngine... engines) {
        return new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(engines)));
    }
}
