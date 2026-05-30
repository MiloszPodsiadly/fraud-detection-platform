package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.unavailableResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudScoringOrchestratorRegistryBoundaryTest {

    @Test
    void missingMlRegistrationIsDifferentFromMlUnavailable() {
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(
                ruleEngine(availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_REGISTRY_EXPECTED_ENGINE_MISSING");

        FraudScoringOrchestrationResult result = new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(
                ruleEngine(availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                mlEngine(unavailableResult(mlDescriptor()))
        ))).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
    }

    @Test
    void missingRulesRegistrationIsConstructionFailure() {
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(
                mlEngine(unavailableResult(mlDescriptor()))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_REGISTRY_REQUIRED_ENGINE_MISSING");
    }
}
