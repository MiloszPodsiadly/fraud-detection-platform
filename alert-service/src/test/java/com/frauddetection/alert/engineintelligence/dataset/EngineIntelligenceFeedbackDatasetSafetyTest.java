package com.frauddetection.alert.engineintelligence.dataset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceFeedbackDatasetSafetyTest {

    @Test
    void diagnosticSignalsRejectFreeText() {
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.requireMachineCode(
                "human readable free text",
                "diagnosticSignals"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticSignalsRejectRawIdentifierPatterns() {
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.requireMachineCode(
                "DEVICE_ID_ABC123",
                "diagnosticSignals"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonCodesRejectRawIdentifierPatterns() {
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.requireMachineCode(
                "CUSTOMER_123_FLAGGED",
                "reasonCodes"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rawIdPatternsRejectedInReasonCodesAndDiagnosticSignals() {
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.requireMachineCode(
                "CUSTOMER_123_FLAGGED",
                "reasonCodes"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.requireMachineCode(
                "DEVICE_ID_ABC123",
                "diagnosticSignals"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelNameRejectsUrlPathTokenSecret() {
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.optionalSafeIdentifier(
                "https://registry.internal/model",
                "modelName"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.optionalSafeIdentifier(
                "model-secret-token",
                "modelName"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelVersionRejectsUrlPathTokenSecret() {
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.optionalSafeIdentifier(
                "s3://bucket/version",
                "modelVersion"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.optionalSafeIdentifier(
                "version-token-1",
                "modelVersion"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outputRejectsEmailLikeValues() {
        assertUnsafe("USER_EXAMPLE_COM_jane@example.com");
    }

    @Test
    void outputRejectsIbanLikeValues() {
        assertUnsafe("PL61109010140000071219812874");
    }

    @Test
    void outputRejectsPanLikeValues() {
        assertUnsafe("4111111111111111");
    }

    @Test
    void outputRejectsEndpointLikeValues() {
        assertUnsafe("https://internal.example/export");
    }

    @Test
    void outputRejectsTokenSecretLikeValues() {
        assertUnsafe("TOKEN_ABC123");
    }

    @Test
    void outputRejectsStacktraceLikeValues() {
        assertUnsafe("java.lang.RuntimeException at com.example.Service(Service.java:10)");
    }

    private void assertUnsafe(String value) {
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.rejectUnsafeValue(value, "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
