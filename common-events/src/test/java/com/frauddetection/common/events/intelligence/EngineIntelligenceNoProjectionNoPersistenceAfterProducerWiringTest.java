package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoProjectionNoPersistenceAfterProducerWiringTest {

    @Test
    void alertServiceStillDoesNotProjectOrPersistEngineIntelligence() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources("alert-service/src/main"))
                .doesNotContain(
                        "EngineIntelligenceSummary", "engineIntelligence", "engineResults",
                        "diagnosticSignals", "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket",
                        "engine_intelligence", "engine_results", "diagnostic_signals",
                        "agreement_status", "risk_mismatch", "score_delta"
                );
    }
}
