package com.frauddetection.common.events.engine;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FraudEngineResultValidationTest {

    @Test
    void rejectsScoreBelowZero() {
        assertThatThrownBy(() -> result(-0.01d, 4L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score");
    }

    @Test
    void rejectsScoreAboveOne() {
        assertThatThrownBy(() -> result(1.01d, 4L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score");
    }

    @Test
    void rejectsNegativeLatency() {
        assertThatThrownBy(() -> result(0.50d, -1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latencyMs");
    }

    @Test
    void requiresEngineIdAndGenerationTime() {
        assertThatThrownBy(() -> new FraudEngineResult(
                " ",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.AVAILABLE,
                0.10d,
                null,
                FraudEngineConfidence.LOW,
                List.of(),
                List.of(),
                List.of(),
                1L,
                null,
                null,
                null,
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("engineId");

        assertThatThrownBy(() -> new FraudEngineResult(
                "rules-v1",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.AVAILABLE,
                0.10d,
                null,
                FraudEngineConfidence.LOW,
                List.of(),
                List.of(),
                List.of(),
                1L,
                null,
                null,
                null,
                null
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("generatedAt");
    }

    @Test
    void unavailableResultUsesBoundedFallbackCodeInsteadOfExceptionText() {
        FraudEngineResult unavailable = result(null, 30L, "MODEL_RUNTIME_UNAVAILABLE");

        assertThat(unavailable.fallbackReason()).isEqualTo("MODEL_RUNTIME_UNAVAILABLE");
        assertThatThrownBy(() -> result(null, 30L, "connection refused at internal host"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded reason code");
    }

    @Test
    void resultDoesNotExposeRawSensitivePayloadFields() {
        assertThat(Arrays.stream(FraudEngineResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain(
                        "rawPayload",
                        "rawFeatures",
                        "customerPayload",
                        "stackTrace",
                        "exception",
                        "token",
                        "secret"
                );
    }

    private FraudEngineResult result(Double score, Long latencyMs, String fallbackReason) {
        return new FraudEngineResult(
                "python-ml-v1",
                FraudEngineType.ML_MODEL,
                "python",
                score == null ? FraudEngineStatus.UNAVAILABLE : FraudEngineStatus.AVAILABLE,
                score,
                null,
                score == null ? FraudEngineConfidence.UNKNOWN : FraudEngineConfidence.MEDIUM,
                List.of(),
                List.of(),
                List.of(),
                latencyMs,
                "python-logistic-fraud-model",
                "v1",
                fallbackReason,
                Instant.parse("2026-05-25T09:00:00Z")
        );
    }
}
