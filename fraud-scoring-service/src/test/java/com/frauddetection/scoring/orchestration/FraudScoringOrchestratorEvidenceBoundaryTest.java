package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.throwingRuleEngine;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorEvidenceBoundaryTest {

    @Test
    void orchestrationFailureEvidenceDoesNotImplyBusinessFallback() throws Exception {
        FraudScoringOrchestrationResult result = new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(
                throwingRuleEngine(new IllegalStateException("secret token endpoint stacktrace")),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        ))).evaluate(context());

        assertThat(result.engineResults().getFirst().evidence().getFirst().evidenceType())
                .isEqualTo(FraudEngineEvidenceType.OPERATIONAL_FALLBACK);
        assertThat(recordComponentNames(FraudScoringOrchestrationResult.class))
                .doesNotContain("fallbackDecision", "approved", "declined", "recommendedAction");
        assertThat(result.engineResults().getFirst().statusReason())
                .isEqualTo(OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION.wireValue());

        String docs = Files.readString(Path.of("..", "docs", "architecture", "fraud_scoring_orchestrator.md"))
                .toLowerCase();
        assertThat(docs)
                .contains("fdp-89 uses existing `fraudengineevidencetype.operational_fallback`")
                .contains("does not mean the orchestrator performed business fallback decisioning")
                .contains("fdp-89 does not change common-events evidence taxonomy");
    }

    private String[] recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}
