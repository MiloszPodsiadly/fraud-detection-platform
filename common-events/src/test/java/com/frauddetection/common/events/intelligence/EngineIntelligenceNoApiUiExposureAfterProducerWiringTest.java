package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoApiUiExposureAfterProducerWiringTest {

    @Test
    void apiUiAndFeedbackWorkflowStillDoNotExposeEngineIntelligence() throws Exception {
        String backendAndFeedbackExposure = EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/api"
        ) + EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/controller"
        ) + EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/mapper"
        );

        assertThat(backendAndFeedbackExposure).doesNotContain(
                "EngineIntelligenceSummary", "engineIntelligence", "engineResults",
                "diagnosticSignals", "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket",
                "winningEngine"
        );
        assertThat(EngineIntelligenceFdp93SourceScanSupport.filesContainingAny(
                "analyst-console-ui/src",
                List.of("engineIntelligence", "engineResults", "diagnosticSignals",
                        "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket")
        )).isSubsetOf(
                EngineIntelligenceFdp93SourceScanSupport.FDP97_ANALYST_CONSOLE_ENGINE_INTELLIGENCE_ALLOWED_FILES
        );
    }
}
