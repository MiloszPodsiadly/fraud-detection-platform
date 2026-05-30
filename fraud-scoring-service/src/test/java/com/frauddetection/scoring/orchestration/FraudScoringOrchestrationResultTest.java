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
        var warnings = new ArrayList<>(List.of("REQUIRED_ENGINE_NOT_AVAILABLE"));

        FraudScoringOrchestrationResult result = new FraudScoringOrchestrationResult(
                engineResults,
                warnings,
                Instant.parse("2026-05-30T10:00:00Z")
        );
        engineResults.clear();
        warnings.clear();

        assertThat(result.engineResults()).hasSize(1);
        assertThat(result.executionWarnings()).containsExactly("REQUIRED_ENGINE_NOT_AVAILABLE");
        assertThatThrownBy(() -> result.engineResults().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.executionWarnings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullEngineResultsList() {
        assertThatThrownBy(() -> new FraudScoringOrchestrationResult(
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
                List.of(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                List.of(),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("generatedAt is required");
    }

    @Test
    void rejectsUnboundedWarnings() {
        assertThatThrownBy(() -> new FraudScoringOrchestrationResult(
                List.of(availableResult(ruleDescriptor(), 0.42d, RiskLevel.LOW)),
                List.of("secret token endpoint stacktrace"),
                Instant.parse("2026-05-30T10:00:00Z")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("executionWarnings must contain bounded warning codes only");
    }
}
