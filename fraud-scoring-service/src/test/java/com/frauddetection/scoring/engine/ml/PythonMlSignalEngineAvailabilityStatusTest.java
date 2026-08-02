package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.domain.FraudScoreResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.context;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.flatten;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.result;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.sourceReturning;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.sourceThrowing;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.unavailableResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonMlSignalEngineAvailabilityStatusTest {

    @Test
    void mlServiceUnavailableDoesNotReturnLowRisk() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(sourceReturning(unavailableResult())).evaluate(context());

        assertFailure(result, FraudEngineStatus.UNAVAILABLE, PythonMlSignalReasonCode.ML_MODEL_UNAVAILABLE);
    }

    @Test
    void modelAvailableFalseWithNullScoreAndNullRiskMapsToUnavailable() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceReturning(result(null, null, null, null, false, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.UNAVAILABLE, PythonMlSignalReasonCode.ML_MODEL_UNAVAILABLE);
        assertThat(flatten(result)).doesNotContain("false");
    }

    @Test
    void missingModelAvailableMetadataDoesNotReturnAvailable() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceReturning(result(0.82d, RiskLevel.HIGH, "python-logistic-fraud-model", "2026-05-30.v1", null, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_AVAILABILITY_METADATA_MISSING);
    }

    @Test
    void nonBooleanModelAvailableMetadataDoesNotReturnAvailable() {
        FraudScoreResult source = new FraudScoreResult(
                0.82d,
                RiskLevel.HIGH,
                "ML",
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                Instant.parse("2026-05-30T09:59:59Z"),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of("modelAvailable", "false"),
                true
        );

        FraudSignalEvaluation result = new PythonMlSignalEngine(sourceReturning(source)).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_AVAILABILITY_METADATA_INVALID);
        assertThat(flatten(result)).doesNotContain("false");
    }

    @Test
    void mlTimeoutDoesNotReturnLowRisk() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceThrowing(new RuntimeException(new TimeoutException("timeout host token stacktrace")))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.TIMEOUT, PythonMlSignalReasonCode.ML_MODEL_TIMEOUT);
        assertThat(flatten(result)).doesNotContain("timeout host token stacktrace");
    }

    @Test
    void socketTimeoutDoesNotReturnLowRisk() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceThrowing(new RuntimeException(new SocketTimeoutException("http://ml-internal token")))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.TIMEOUT, PythonMlSignalReasonCode.ML_MODEL_TIMEOUT);
        assertThat(flatten(result)).doesNotContain("http://ml-internal", "token");
    }

    @Test
    void mlClientExceptionDoesNotLeakExceptionMessage() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceThrowing(new RestClientException("raw response body token endpoint stacktrace"))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.UNAVAILABLE, PythonMlSignalReasonCode.ML_CLIENT_ERROR);
        assertThat(flatten(result)).doesNotContain("raw response body", "token", "endpoint", "stacktrace");
    }

    @Test
    void programmerRuntimeExceptionPropagatesToOrchestratorIsolation() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(
                sourceThrowing(new IllegalStateException("programmer bug token stacktrace"))
        );

        assertThatThrownBy(() -> adapter.evaluate(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("programmer bug");
    }

    @Test
    void nullPointerExceptionPropagatesToOrchestratorIsolation() {
        PythonMlSignalEngine adapter = new PythonMlSignalEngine(
                sourceThrowing(new NullPointerException("secret token"))
        );

        assertThatThrownBy(() -> adapter.evaluate(context()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void modelAvailableTrueWithMissingRiskReturnsDegraded() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceReturning(result(0.50d, null, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_MODEL_INVALID_RESPONSE);
    }

    @Test
    void modelAvailableTrueWithMissingScoreReturnsDegraded() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceReturning(result(null, RiskLevel.HIGH, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_SCORE_MISSING);
    }

    @Test
    void scoreBelowZeroReturnsDegraded() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceReturning(result(-0.01d, RiskLevel.LOW, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_SCORE_OUT_OF_RANGE);
    }

    @Test
    void scoreAboveOneReturnsDegraded() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceReturning(result(1.01d, RiskLevel.CRITICAL, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_SCORE_OUT_OF_RANGE);
    }

    @Test
    void nullResponseReturnsDegradedOrUnavailable() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(sourceReturning(null)).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_MODEL_INVALID_RESPONSE);
    }

    @Test
    void emptyResponseWithoutAvailabilityMetadataReturnsDegraded() {
        FraudScoreResult empty = new FraudScoreResult(
                null,
                null,
                "ML",
                null,
                null,
                Instant.parse("2026-05-30T09:59:59Z"),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                false
        );

        FraudSignalEvaluation result = new PythonMlSignalEngine(sourceReturning(empty)).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_AVAILABILITY_METADATA_MISSING);
    }

    @Test
    void modelAvailableTrueWithMissingModelMetadataReturnsDegraded() {
        FraudSignalEvaluation result = new PythonMlSignalEngine(
                sourceReturning(result(0.82d, RiskLevel.HIGH, null, "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_MODEL_METADATA_MISSING);
    }

    private void assertFailure(
            FraudSignalEvaluation result,
            FraudEngineStatus expectedStatus,
            PythonMlSignalReasonCode expectedReason
    ) {
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.statusReason()).isEqualTo(expectedReason.wireValue());
        assertThat(result.reasonCodes()).containsExactly(expectedReason.wireValue());
        assertThat(result.evidence()).extracting(evidence -> evidence.reasonCode())
                .containsExactly(expectedReason.wireValue());
        assertThat(flatten(result)).doesNotContain("LOW");
    }
}
