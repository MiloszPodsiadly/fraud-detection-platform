package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.flatten;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.velocityDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.warningCodes;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorPublicationBoundaryTest {

    @Test
    void optionalVelocityInvalidEvaluationBecomesOperationalPublicationFailureWithoutDroppingRulesOrMl() {
        FraudScoringOrchestrationResult result = orchestrator(invalidAvailableEvaluation()).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertThat(result.engineResults()).hasSize(3);
        assertThat(result.engineResults().get(0).status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertPublicationFailure(result.engineResults().get(2));
        assertThat(result.engineResults().get(0).score()).isEqualTo(0.82d);
        assertThat(result.engineResults().get(1).score()).isEqualTo(0.72d);
        assertThat(warningCodes(result)).contains(
                FraudScoringExecutionWarningCode.OPTIONAL_ENGINE_NOT_AVAILABLE,
                FraudScoringExecutionWarningCode.ENGINE_PUBLICATION_FAILURE_RECORDED,
                FraudScoringExecutionWarningCode.ENGINE_DEGRADED_RECORDED
        );
        assertThat(flatten(result)).doesNotContain("LOW", "score must", "raw-token", "accountId");
    }

    @Test
    void optionalVelocityNullEvaluationBecomesOperationalEvaluationFailure() {
        var velocity = new FraudScoringOrchestratorTestSupport.FakeFraudSignalEngine(
                velocityDescriptor(),
                ignored -> null
        );

        FraudScoringOrchestrationResult result = new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(
                ruleEngine(availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM)),
                velocity
        ))).evaluate(context());

        FraudEngineResult velocityResult = result.engineResults().get(2);

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertThat(velocityResult.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(velocityResult.statusReason()).isEqualTo("ORCHESTRATOR_ENGINE_NULL_RESULT");
        assertThat(velocityResult.score()).isNull();
        assertThat(velocityResult.riskLevel()).isNull();
        assertThat(warningCodes(result)).contains(
                FraudScoringExecutionWarningCode.ENGINE_EVALUATION_FAILURE_RECORDED,
                FraudScoringExecutionWarningCode.ENGINE_DEGRADED_RECORDED
        );
    }

    @Test
    void publicationEnvelopeRejectsUnboundedStatusReasonAsOperationalPublicationFailure() {
        FraudSignalEvaluation evaluation = new FraudSignalEvaluation(
                FraudEngineStatus.DEGRADED,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of("ENGINE_DEGRADED"),
                List.of(),
                List.of(),
                null,
                null,
                "raw-token-accountId-stacktrace"
        );

        FraudScoringOrchestrationResult result = orchestrator(evaluation).evaluate(context());

        assertPublicationFailure(result.engineResults().get(2));
        assertThat(flatten(result)).doesNotContain("raw-token", "accountId", "stacktrace");
    }

    @Test
    void publicationEnvelopeRejectsTooManyReasonCodesAsOperationalPublicationFailure() {
        FraudSignalEvaluation evaluation = new FraudSignalEvaluation(
                FraudEngineStatus.AVAILABLE,
                0.75d,
                RiskLevel.HIGH,
                FraudEngineConfidence.UNKNOWN,
                Collections.nCopies(11, "TRANSACTION_VELOCITY"),
                List.of(),
                List.of(),
                null,
                null,
                null
        );

        FraudScoringOrchestrationResult result = orchestrator(evaluation).evaluate(context());

        assertPublicationFailure(result.engineResults().get(2));
    }

    private FraudScoringOrchestrator orchestrator(FraudSignalEvaluation velocityEvaluation) {
        var velocity = new FraudScoringOrchestratorTestSupport.FakeFraudSignalEngine(
                velocityDescriptor(),
                ignored -> velocityEvaluation
        );
        return new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(
                ruleEngine(availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                mlEngine(availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM)),
                velocity
        )));
    }

    private FraudSignalEvaluation invalidAvailableEvaluation() {
        return new FraudSignalEvaluation(
                FraudEngineStatus.AVAILABLE,
                1.1d,
                RiskLevel.HIGH,
                FraudEngineConfidence.UNKNOWN,
                List.of("TRANSACTION_VELOCITY"),
                List.of(),
                List.of(),
                null,
                null,
                null
        );
    }

    private void assertPublicationFailure(FraudEngineResult result) {
        assertThat(result.engineId()).isEqualTo("velocity.primary");
        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("ORCHESTRATOR_ENGINE_PUBLICATION_FAILURE");
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.reasonCodes()).containsExactly("ORCHESTRATOR_ENGINE_PUBLICATION_FAILURE");
    }
}
