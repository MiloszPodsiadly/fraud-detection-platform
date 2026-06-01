package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoLoggingExposureTest {

    @Test
    void alertServiceProductionCodeDoesNotLogOrSerializeEngineIntelligence() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources("alert-service/src/main/java"))
                .withFailMessage("ENGINE_INTELLIGENCE_LOGGING_EXPOSURE_OUT_OF_SCOPE")
                .doesNotContain(
                        ".engineIntelligence(",
                        "engineIntelligence()",
                        "EngineIntelligenceSummary",
                        "diagnosticSignals",
                        "agreementStatus",
                        "riskMismatchStatus",
                        "scoreDeltaBucket",
                        "log.info(\"{}\", event)",
                        "log.debug(\"{}\", event)",
                        "ObjectMapper.writeValueAsString(event)",
                        "String.valueOf(event)",
                        "event.toString()"
                );
    }
}
