package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoApiUiExposureTest {

    @Test
    void apiAndControllerProductionCodeDoNotExposeEngineIntelligence() throws Exception {
        String apiSources = EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/api"
        ) + EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/controller"
        ) + EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/suspicious/api"
        );

        assertThat(apiSources).doesNotContain(
                "EngineIntelligenceSummary", "engineIntelligence", "diagnosticSignals",
                "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket"
        );
    }

    @Test
    void analystConsoleDoesNotExposeEngineIntelligence() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources("analyst-console-ui/src"))
                .doesNotContain(
                        "engineIntelligence", "diagnosticSignals", "agreementStatus",
                        "riskMismatchStatus", "scoreDeltaBucket"
                );
    }
}
