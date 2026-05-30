package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.engineIds;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudScoringOrchestratorExecutionOrderTest {

    @Test
    void orchestratorRunsRulesThenMlInDeterministicOrder() {
        FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM)),
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW))
        )));

        assertThat(engineIds(orchestrator.evaluate(context())))
                .containsExactly("rules.primary", "ml.python.primary");
    }

    @Test
    void repeatedEvaluationUsesSameEngineOrder() {
        FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM)),
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW))
        )));

        assertThat(engineIds(orchestrator.evaluate(context()))).containsExactly("rules.primary", "ml.python.primary");
        assertThat(engineIds(orchestrator.evaluate(context()))).containsExactly("rules.primary", "ml.python.primary");
        assertThat(engineIds(orchestrator.evaluate(context()))).containsExactly("rules.primary", "ml.python.primary");
    }

    @Test
    void registryRejectsDuplicateEngineIds() {
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                ruleEngine(availableResult(ruleDescriptor(), 0.50d, RiskLevel.MEDIUM))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_REGISTRY_DUPLICATE_ENGINE_ID")
                .hasMessageNotContaining("FakeFraudSignalEngine");
    }

    @Test
    void registryRejectsUnknownEngineIds() {
        FraudEngineDescriptor unknownDescriptor = new FraudEngineDescriptor(
                "velocity.primary",
                FraudEngineType.VELOCITY,
                "java",
                "1.0.0",
                false
        );

        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(engineReturningDescriptor(unknownDescriptor))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_REGISTRY_UNKNOWN_ENGINE_ID")
                .hasMessageNotContaining("velocity.primary");
    }

    @Test
    void registryRejectsNullEngine() {
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(Arrays.asList(
                ruleEngine(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                null
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_REGISTRY_NULL_ENGINE");
    }

    @Test
    void registryRejectsNullDescriptor() {
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(engineReturningDescriptor(null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_REGISTRY_NULL_DESCRIPTOR");
    }

    @Test
    void registryRejectsBlankEngineId() {
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(engineWithInvalidDescriptor())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_REGISTRY_INVALID_DESCRIPTOR");
    }

    private FraudSignalEngine engineReturningDescriptor(FraudEngineDescriptor descriptor) {
        return new FraudSignalEngine() {
            @Override
            public com.frauddetection.common.events.engine.FraudEngineResult evaluate(
                    com.frauddetection.scoring.context.ScoringContext context
            ) {
                return availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW);
            }

            @Override
            public FraudEngineDescriptor descriptor() {
                return descriptor;
            }
        };
    }

    private FraudSignalEngine engineWithInvalidDescriptor() {
        return new FraudSignalEngine() {
            @Override
            public com.frauddetection.common.events.engine.FraudEngineResult evaluate(
                    com.frauddetection.scoring.context.ScoringContext context
            ) {
                return availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW);
            }

            @Override
            public FraudEngineDescriptor descriptor() {
                return new FraudEngineDescriptor(" ", FraudEngineType.RULES, "java", "1.0.0", true);
            }
        };
    }
}
