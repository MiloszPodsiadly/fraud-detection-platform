package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.flatten;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.throwingMlEngine;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorExceptionSafetyTest {

    @Test
    void engineExceptionMessageIsNeverCopied() {
        FraudScoringOrchestrationResult result = orchestrator(new IllegalStateException(
                "raw-token endpoint http://internal stacktrace accountId=123 featureVector=VIP"
        ));

        assertBoundedFailure(result);
        assertThat(flatten(result))
                .doesNotContain("raw-token", "endpoint", "http://internal", "stacktrace", "accountId", "featureVector", "VIP");
    }

    @Test
    void nestedExceptionCauseMessageIsNeverCopied() {
        FraudScoringOrchestrationResult result = orchestrator(new RuntimeException(
                "outer secret",
                new IllegalStateException("inner token stacktrace")
        ));

        assertBoundedFailure(result);
        assertThat(flatten(result)).doesNotContain("outer secret", "inner token", "stacktrace");
    }

    @Test
    void exceptionClassNameIsNotExposed() {
        FraudScoringOrchestrationResult result = orchestrator(new RawTokenEndpointStacktraceException());

        assertBoundedFailure(result);
        assertThat(flatten(result)).doesNotContain("RawTokenEndpointStacktraceException");
    }

    @Test
    void warningsDoNotContainRawExceptionText() {
        FraudScoringOrchestrationResult result = orchestrator(new IllegalStateException(
                "raw-token endpoint http://internal stacktrace accountId=123 featureVector=VIP"
        ));

        assertThat(result.executionWarnings()).hasSize(2);
        assertThat(result.executionWarnings()).allSatisfy(warning -> {
            assertThat(warning.engineId()).isEqualTo("ml.python.primary");
            assertThat(warning.code()).isInstanceOf(FraudScoringExecutionWarningCode.class);
            assertThat(warning.toString())
                    .doesNotContain("raw-token", "endpoint", "http://internal", "stacktrace", "accountId", "featureVector", "VIP");
        });
    }

    @Test
    void orchestratorSourceDoesNotReadExceptionMessagesOrStackTraces() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/scoring/orchestration/FraudScoringOrchestrator.java"
        ));

        assertThat(source)
                .doesNotContain("exception.getMessage()")
                .doesNotContain("getLocalizedMessage()")
                .doesNotContain("printStackTrace")
                .doesNotContain("StackTraceElement")
                .doesNotContain("exception.toString()");
    }

    private FraudScoringOrchestrationResult orchestrator(RuntimeException exception) {
        return new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(
                ruleEngine(availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                throwingMlEngine(exception)
        ))).evaluate(context());
    }

    private void assertBoundedFailure(FraudScoringOrchestrationResult result) {
        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.engineResults().get(1).statusReason())
                .isEqualTo(OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION.wireValue());
        assertThat(result.engineResults().get(1).evidence()).hasSize(1);
        assertThat(result.engineResults().get(1).evidence().getFirst().reasonCode())
                .isEqualTo(OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION.wireValue());
    }

    private static final class RawTokenEndpointStacktraceException extends RuntimeException {
    }
}
