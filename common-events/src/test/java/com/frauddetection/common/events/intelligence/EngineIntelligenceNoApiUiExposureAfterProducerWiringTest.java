package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoApiUiExposureAfterProducerWiringTest {

    @Test
    void apiUiAndFeedbackWorkflowStillDoNotExposeEngineIntelligence() throws Exception {
        String productExposure = EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/api"
        ) + EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/controller"
        ) + EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/mapper"
        ) + EngineIntelligenceFdp93SourceScanSupport.sources(
                "analyst-console-ui/src"
        );

        assertThat(productExposure).doesNotContain(
                "EngineIntelligenceSummary", "engineIntelligence", "engineResults",
                "diagnosticSignals", "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket",
                "winningEngine"
        );
    }
}
