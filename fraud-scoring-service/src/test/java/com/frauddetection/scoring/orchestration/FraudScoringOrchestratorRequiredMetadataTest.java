package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineResult;
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
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.throwingRuleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.unavailableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.warningCodes;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorRequiredMetadataTest {

    @Test
    void requiredFailureDoesNotStopOptionalEngineExecution() {
        FraudScoringOrchestrationResult result = orchestrator(
                throwingRuleEngine(new IllegalStateException("secret token endpoint stacktrace")),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertThat(result.engineResults()).hasSize(2);
        assertThat(engineIds(result)).containsExactly("rules.primary", "ml.python.primary");
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
        assertThat(warningCodes(result)).contains(FraudScoringExecutionWarningCode.REQUIRED_ENGINE_NOT_AVAILABLE);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertNoDecisionSurface();
    }

    @Test
    void optionalFailureDoesNotChangeRequiredEngineResult() {
        FraudEngineResult ruleResult = availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW);

        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(ruleResult),
                mlEngine(unavailableResult(mlDescriptor()))
        ).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertBusinessSignalPreservedWithPublishedMetadata(result.engineResults().getFirst(), ruleResult);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(warningCodes(result)).contains(FraudScoringExecutionWarningCode.OPTIONAL_ENGINE_NOT_AVAILABLE);
        assertNoDecisionSurface();
    }

    @Test
    void requiredFlagDoesNotMutateEngineResult() {
        FraudEngineResult ruleResult = degradedResult(ruleDescriptor());

        FraudScoringOrchestrationResult result = orchestrator(
                ruleEngine(ruleResult),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ).evaluate(context());

        assertBusinessSignalPreservedWithPublishedMetadata(result.engineResults().getFirst(), ruleResult);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
        assertThat(warningCodes(result)).contains(FraudScoringExecutionWarningCode.REQUIRED_ENGINE_NOT_AVAILABLE);
    }

    @Test
    void requiredMetadataDoesNotCreateFailClosedRuntimeDecision() {
        assertNoDecisionSurface();
    }

    private FraudScoringOrchestrator orchestrator(FraudScoringOrchestratorTestSupport.FakeFraudSignalEngine... engines) {
        return new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(engines)));
    }

    private void assertBusinessSignalPreservedWithPublishedMetadata(
            FraudEngineResult published,
            FraudEngineResult source
    ) {
        assertThat(published).isNotSameAs(source);
        assertThat(published.engineId()).isEqualTo(source.engineId());
        assertThat(published.engineType()).isEqualTo(source.engineType());
        assertThat(published.engineLanguage()).isEqualTo(source.engineLanguage());
        assertThat(published.status()).isEqualTo(source.status());
        assertThat(published.score()).isEqualTo(source.score());
        assertThat(published.riskLevel()).isEqualTo(source.riskLevel());
        assertThat(published.confidence()).isEqualTo(source.confidence());
        assertThat(published.reasonCodes()).isEqualTo(source.reasonCodes());
        assertThat(published.contributions()).isEqualTo(source.contributions());
        assertThat(published.evidence()).isEqualTo(source.evidence());
        assertThat(published.modelName()).isEqualTo(source.modelName());
        assertThat(published.modelVersion()).isEqualTo(source.modelVersion());
        assertThat(published.statusReason()).isEqualTo(source.statusReason());
        assertThat(published.generatedAt()).isNotEqualTo(source.generatedAt());
        assertThat(published.latencyMs()).isGreaterThanOrEqualTo(0L);
    }

    private void assertNoDecisionSurface() {
        assertThat(Arrays.stream(FraudScoringOrchestrationResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain(
                        "approve",
                        "decline",
                        "block",
                        "recommendedAction",
                        "finalDecision",
                        "finalRisk",
                        "overallRisk"
                );
        assertThat(Arrays.stream(FraudScoringOrchestrator.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("approve", "decline", "block", "recommendedAction", "finalDecision", "finalRisk", "overallRisk");
    }
}
