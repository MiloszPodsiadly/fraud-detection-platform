package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceNoApiUiExposureAfterProducerWiringTest {

    private static final List<String> SCORED_TRANSACTION_DETAIL_ALLOWED_BACKEND_FILES = List.of(
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceComparisonResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceDiagnosticSignalResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceEngineResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceEngineStatusResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceResponseStatus.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceWarningResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/ScoredTransactionDetailResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/ScoredTransactionResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/controller/ScoredTransactionController.java",
            "alert-service/src/main/java/com/frauddetection/alert/mapper/EngineIntelligenceResponseMapper.java",
            // Approved only to persist the bounded ML evidence outcome; it does not expose Engine Intelligence.
            "alert-service/src/main/java/com/frauddetection/alert/mapper/ScoredTransactionDocumentMapper.java",
            "alert-service/src/main/java/com/frauddetection/alert/mapper/ScoredTransactionResponseMapper.java"
    );

    @Test
    void apiUiAndFeedbackWorkflowExposeEngineIntelligenceOnlyThroughApprovedSurfaces() throws Exception {
        List<String> backendExposure = EngineIntelligenceSourceScanSupport.filesContainingAny(
                "alert-service/src/main/java/com/frauddetection/alert",
                List.of("EngineIntelligenceSummary", "engineIntelligence", "engineResults",
                        "diagnosticSignals", "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket",
                        "winningEngine", "mlPredictionEvidence", "MlPredictionEvidence")
        ).stream()
                .filter(file -> file.startsWith("alert-service/src/main/java/com/frauddetection/alert/api/")
                        || file.startsWith("alert-service/src/main/java/com/frauddetection/alert/controller/")
                        || file.startsWith("alert-service/src/main/java/com/frauddetection/alert/mapper/"))
                .toList();

        assertOnlyApprovedBackendExposure(backendExposure);
        assertThat(EngineIntelligenceSourceScanSupport.filesContainingAny(
                "analyst-console-ui/src",
                List.of("engineIntelligence", "engineResults", "diagnosticSignals",
                        "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket", "mlPredictionEvidence")
        )).isSubsetOf(
                EngineIntelligenceSourceScanSupport.ANALYST_CONSOLE_ENGINE_INTELLIGENCE_ALLOWED_FILES
        );
    }

    @Test
    void arbitraryAdditionalMapperRemainsRejected() {
        assertThatThrownBy(() -> assertOnlyApprovedBackendExposure(List.of(
                "alert-service/src/main/java/com/frauddetection/alert/mapper/UnapprovedProjectionMapper.java"
        ))).isInstanceOf(AssertionError.class);
    }

    private static void assertOnlyApprovedBackendExposure(List<String> backendExposure) {
        assertThat(backendExposure).isSubsetOf(SCORED_TRANSACTION_DETAIL_ALLOWED_BACKEND_FILES);
    }
}
