package com.frauddetection.scoring.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackReasonCodesTest {

    @Test
    void nullAndBlankReasonsBecomeUnknown() {
        assertThat(FallbackReasonCodes.from(null)).isEqualTo("unknown_fallback_reason");
        assertThat(FallbackReasonCodes.from(" ")).isEqualTo("unknown_fallback_reason");
    }

    @Test
    void requestFailuresBecomeRequestFailedCode() {
        assertThat(FallbackReasonCodes.from("ML inference service request failed at http://internal"))
                .isEqualTo("ml_request_failed");
    }

    @Test
    void invalidOrEmptyResponsesBecomeInvalidResponseCode() {
        assertThat(FallbackReasonCodes.from("ML response invalid"))
                .isEqualTo("ml_response_invalid");
        assertThat(FallbackReasonCodes.from("ML returned empty response"))
                .isEqualTo("ml_response_invalid");
    }

    @Test
    void runtimeReasonsBecomeRuntimeUnavailableCode() {
        assertThat(FallbackReasonCodes.from("No ML model runtime is configured yet."))
                .isEqualTo("ml_runtime_unavailable");
    }

    @Test
    void unavailableOrNotConfiguredReasonsBecomeModelUnavailableCode() {
        assertThat(FallbackReasonCodes.from("ML model unavailable"))
                .isEqualTo("ml_model_unavailable");
        assertThat(FallbackReasonCodes.from("ML model not configured"))
                .isEqualTo("ml_model_unavailable");
    }

    @Test
    void fallbackReasonsBecomeScoringFallbackCode() {
        assertThat(FallbackReasonCodes.from("Fallback scoring path used"))
                .isEqualTo("scoring_fallback_used");
    }
}
