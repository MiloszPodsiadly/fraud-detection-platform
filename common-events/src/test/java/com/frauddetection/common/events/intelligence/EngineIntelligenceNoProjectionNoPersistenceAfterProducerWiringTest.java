package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoProjectionNoPersistenceAfterProducerWiringTest {

    @Test
    void alertServiceProjectionRemainsIsolatedFromBaseDocument() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.read(
                "alert-service/src/main/java/com/frauddetection/alert/persistence/ScoredTransactionDocument.java"
        ) + EngineIntelligenceFdp93SourceScanSupport.read(
                "alert-service/src/main/java/com/frauddetection/alert/mapper/ScoredTransactionDocumentMapper.java"
        ))
                .doesNotContain(
                        "EngineIntelligenceSummary", "engineIntelligence", "engineResults", "diagnosticSignals"
                );
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence"
        )).contains("engine_intelligence_projections", "EngineIntelligenceProjectionService");
    }

    @Test
    void relationalDatabaseMigrationsStillDoNotContainEngineIntelligence() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources("alert-service/src/main/resources/db/migration"))
                .doesNotContain(
                        "engineIntelligence", "engineResults", "diagnosticSignals",
                        "engine_intelligence", "engine_results", "diagnostic_signals"
                );
    }
}
