package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoApiUiExposureTest {

    private static final List<String> FDP115_SCORED_TRANSACTION_DETAIL_ALLOWED_BACKEND_FILES = List.of(
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceComparisonResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceDiagnosticSignalResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceEngineResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceEngineStatusResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceResponseStatus.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/EngineIntelligenceWarningResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/ScoredTransactionDetailResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/api/ScoredTransactionResponse.java",
            "alert-service/src/main/java/com/frauddetection/alert/controller/ScoredTransactionController.java"
    );

    @Test
    void apiAndControllerProductionCodeExposeEngineIntelligenceOnlyThroughScoredTransactionDetail() throws Exception {
        List<String> apiExposure = EngineIntelligenceFdp93SourceScanSupport.filesContainingAny(
                "alert-service/src/main/java/com/frauddetection/alert",
                List.of("EngineIntelligenceSummary", "engineIntelligence", "diagnosticSignals",
                        "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket")
        ).stream()
                .filter(file -> file.startsWith("alert-service/src/main/java/com/frauddetection/alert/api/")
                        || file.startsWith("alert-service/src/main/java/com/frauddetection/alert/controller/")
                        || file.startsWith("alert-service/src/main/java/com/frauddetection/alert/suspicious/api/"))
                .toList();

        assertThat(apiExposure).isSubsetOf(FDP115_SCORED_TRANSACTION_DETAIL_ALLOWED_BACKEND_FILES);
    }

    @Test
    void analystConsoleExposesEngineIntelligenceOnlyThroughFdp97ReadOnlyDisplay() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.filesContainingAny(
                "analyst-console-ui/src",
                List.of("engineIntelligence", "diagnosticSignals", "agreementStatus",
                        "riskMismatchStatus", "scoreDeltaBucket")
        )).isSubsetOf(
                EngineIntelligenceFdp93SourceScanSupport.FDP97_ANALYST_CONSOLE_ENGINE_INTELLIGENCE_ALLOWED_FILES
        );
    }
}
