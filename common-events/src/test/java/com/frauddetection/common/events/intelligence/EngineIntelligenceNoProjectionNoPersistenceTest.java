package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoProjectionNoPersistenceTest {

    @Test
    void downstreamProductionCodeDoesNotProjectOrPersistEngineIntelligence() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources("alert-service/src/main"))
                .doesNotContain(
                        "EngineIntelligenceSummary", "engineIntelligence", "engineResults",
                        "diagnosticSignals", "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket",
                        "engineIntelligenceWarnings"
                );
    }

    @Test
    void databaseResourcesDoNotAddEngineIntelligenceFields() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources("alert-service/src/main/resources"))
                .doesNotContain(
                        "engine_intelligence", "engine_results", "diagnostic_signals",
                        "agreement_status", "risk_mismatch", "score_delta"
                );
    }
}
