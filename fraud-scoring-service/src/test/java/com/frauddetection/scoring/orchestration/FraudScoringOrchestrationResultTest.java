package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudScoringOrchestrationResultTest {

    @Test
    void defensivelyCopiesEngineResultsAndWarnings() {
        var engineResults = new ArrayList<>(List.of(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)));
        var warnings = new ArrayList<>(List.of(requiredWarning()));

        FraudScoringOrchestrationResult result = new FraudScoringOrchestrationResult(
                FraudScoringOrchestrationStatus.COMPLETE,
                engineResults,
                warnings,
                Instant.parse("2026-05-30T10:00:00Z")
        );
        engineResults.clear();
        warnings.clear();

        assertThat(result.engineResults()).hasSize(1);
        assertThat(result.executionWarnings()).containsExactly(requiredWarning());
        assertThatThrownBy(() -> result.engineResults().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.executionWarnings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullStatus() {
        assertThatThrownBy(() -> new FraudScoringOrchestrationResult(
                null,
                List.of(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                List.of(),
                Instant.parse("2026-05-30T10:00:00Z")
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status is required");
    }

    @Test
    void rejectsNullEngineResultsList() {
        assertThatThrownBy(() -> new FraudScoringOrchestrationResult(
                FraudScoringOrchestrationStatus.COMPLETE,
                null,
                List.of(),
                Instant.parse("2026-05-30T10:00:00Z")
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("engineResults is required");
    }

    @Test
    void rejectsNullEngineResultEntries() {
        List<com.frauddetection.common.events.engine.FraudEngineResult> engineResults = new ArrayList<>();
        engineResults.add(null);

        assertThatThrownBy(() -> new FraudScoringOrchestrationResult(
                FraudScoringOrchestrationStatus.COMPLETE,
                engineResults,
                List.of(),
                Instant.parse("2026-05-30T10:00:00Z")
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("engineResults must not contain null entries");
    }

    @Test
    void rejectsNullGeneratedAt() {
        assertThatThrownBy(() -> new FraudScoringOrchestrationResult(
                FraudScoringOrchestrationStatus.COMPLETE,
                List.of(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                List.of(),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("generatedAt is required");
    }

    @Test
    void rejectsNullWarningEntries() {
        List<FraudScoringExecutionWarning> warnings = new ArrayList<>();
        warnings.add(null);

        assertThatThrownBy(() -> new FraudScoringOrchestrationResult(
                FraudScoringOrchestrationStatus.COMPLETE,
                List.of(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                warnings,
                Instant.parse("2026-05-30T10:00:00Z")
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("executionWarnings must not contain null entries");
    }

    @Test
    void rejectsInvalidWarningEngineId() {
        assertThatThrownBy(() -> new FraudScoringExecutionWarning(
                "secret token endpoint stacktrace",
                FraudScoringExecutionWarningCode.REQUIRED_ENGINE_NOT_AVAILABLE,
                com.frauddetection.common.events.engine.FraudEngineStatus.DEGRADED,
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("engineId must be bounded");
    }

    private FraudScoringExecutionWarning requiredWarning() {
        return new FraudScoringExecutionWarning(
                "rules.primary",
                FraudScoringExecutionWarningCode.REQUIRED_ENGINE_NOT_AVAILABLE,
                com.frauddetection.common.events.engine.FraudEngineStatus.DEGRADED,
                true
        );
    }
}
