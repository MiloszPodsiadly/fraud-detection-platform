package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoProjectionNoPersistenceTest {

    @Test
    void baseScoredTransactionProjectionStillDoesNotEmbedEngineIntelligence() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.read(
                "alert-service/src/main/java/com/frauddetection/alert/persistence/ScoredTransactionDocument.java"
        ) + EngineIntelligenceFdp93SourceScanSupport.read(
                "alert-service/src/main/java/com/frauddetection/alert/mapper/ScoredTransactionDocumentMapper.java"
        ))
                .doesNotContain(
                        "EngineIntelligenceSummary", "engineIntelligence", "engineResults", "diagnosticSignals"
                );
    }

    @Test
    void relationalDatabaseResourcesStillDoNotAddEngineIntelligenceFields() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources("alert-service/src/main/resources"))
                .doesNotContain(
                        "engine_intelligence", "engine_results", "diagnostic_signals",
                        "agreement_status", "risk_mismatch", "score_delta"
                );
    }
}
