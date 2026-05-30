package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.domain.FraudScoreResult;
import org.junit.jupiter.api.Test;

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

class PythonMlSignalEngineAvailabilityStatusTest {

    @Test
    void mlServiceUnavailableDoesNotReturnLowRisk() {
        FraudEngineResult result = new PythonMlSignalEngine(sourceReturning(unavailableResult())).evaluate(context());

        assertFailure(result, FraudEngineStatus.UNAVAILABLE, PythonMlSignalReasonCode.ML_MODEL_UNAVAILABLE);
    }

    @Test
    void missingModelAvailableMetadataDoesNotReturnAvailable() {
        FraudEngineResult result = new PythonMlSignalEngine(
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

        FraudEngineResult result = new PythonMlSignalEngine(sourceReturning(source)).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_AVAILABILITY_METADATA_INVALID);
        assertThat(flatten(result)).doesNotContain("false");
    }

    @Test
    void mlTimeoutDoesNotReturnLowRisk() {
        FraudEngineResult result = new PythonMlSignalEngine(
                sourceThrowing(new RuntimeException(new TimeoutException("timeout host token stacktrace")))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.TIMEOUT, PythonMlSignalReasonCode.ML_MODEL_TIMEOUT);
        assertThat(flatten(result)).doesNotContain("timeout host token stacktrace");
    }

    @Test
    void socketTimeoutDoesNotReturnLowRisk() {
        FraudEngineResult result = new PythonMlSignalEngine(
                sourceThrowing(new RuntimeException(new SocketTimeoutException("http://ml-internal token")))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.TIMEOUT, PythonMlSignalReasonCode.ML_MODEL_TIMEOUT);
        assertThat(flatten(result)).doesNotContain("http://ml-internal", "token");
    }

    @Test
    void mlClientExceptionDoesNotLeakExceptionMessage() {
        FraudEngineResult result = new PythonMlSignalEngine(
                sourceThrowing(new IllegalStateException("raw response body token endpoint stacktrace"))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.UNAVAILABLE, PythonMlSignalReasonCode.ML_CLIENT_ERROR);
        assertThat(flatten(result)).doesNotContain("raw response body", "token", "endpoint", "stacktrace");
    }

    @Test
    void invalidResponseDoesNotReturnAvailable() {
        FraudEngineResult result = new PythonMlSignalEngine(
                sourceReturning(result(0.50d, null, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_MODEL_INVALID_RESPONSE);
    }

    @Test
    void missingScoreReturnsDegraded() {
        FraudEngineResult result = new PythonMlSignalEngine(
                sourceReturning(result(null, RiskLevel.HIGH, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_SCORE_MISSING);
    }

    @Test
    void scoreBelowZeroReturnsDegraded() {
        FraudEngineResult result = new PythonMlSignalEngine(
                sourceReturning(result(-0.01d, RiskLevel.LOW, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_SCORE_OUT_OF_RANGE);
    }

    @Test
    void scoreAboveOneReturnsDegraded() {
        FraudEngineResult result = new PythonMlSignalEngine(
                sourceReturning(result(1.01d, RiskLevel.CRITICAL, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_SCORE_OUT_OF_RANGE);
    }

    @Test
    void nullResponseReturnsDegradedOrUnavailable() {
        FraudEngineResult result = new PythonMlSignalEngine(sourceReturning(null)).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_MODEL_INVALID_RESPONSE);
    }

    @Test
    void emptyResponseReturnsDegraded() {
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

        FraudEngineResult result = new PythonMlSignalEngine(sourceReturning(empty)).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_SCORE_MISSING);
    }

    @Test
    void missingModelMetadataUsesBoundedReason() {
        FraudEngineResult result = new PythonMlSignalEngine(
                sourceReturning(result(0.82d, RiskLevel.HIGH, null, "2026-05-30.v1", true, List.of()))
        ).evaluate(context());

        assertFailure(result, FraudEngineStatus.DEGRADED, PythonMlSignalReasonCode.ML_MODEL_METADATA_MISSING);
    }

    private void assertFailure(
            FraudEngineResult result,
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
