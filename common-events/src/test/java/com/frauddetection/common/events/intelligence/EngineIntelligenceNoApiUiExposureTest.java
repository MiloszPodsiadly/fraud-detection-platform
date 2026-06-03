package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

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
