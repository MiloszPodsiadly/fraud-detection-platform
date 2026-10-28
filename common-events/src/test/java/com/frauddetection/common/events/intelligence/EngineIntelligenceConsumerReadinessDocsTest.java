package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceConsumerReadinessDocsTest {

    @Test
    void docsStateCurrentConsumerMatrixAndStrictCurrentVersionGate() throws Exception {
        String docs = EngineIntelligenceSourceScanSupport.read(
                "docs/architecture/engine_intelligence_consumer_readiness.md"
        );

        assertThat(docs).contains(
                "Current Engine Intelligence consumer readiness",
                "outer `eventContractVersion=2`",
                "exactly `contractVersion=2`",
                "16 KiB of serialized UTF-8",
                "`rules.primary` (required)",
                "`device.primary` (optional and not an admitted runtime)",
                "Current producer and consumer matrix",
                "EngineIntelligenceKafkaPayloadBoundary",
                "JacksonKafkaSerializer<TransactionScoredEvent>",
                "JacksonKafkaDeserializer<TransactionScoredEvent>",
                "TransactionScoredEventListener",
                "EngineIntelligenceProjectionEventListener",
                "MlPredictionEvidenceProjectionService",
                "TransactionFixtures",
                "test scope only",
                "REST / OpenAPI",
                "React",
                "External consumers",
                "Compatibility is unknown until every external consumer is identified and verified",
                "Byte-oriented boundaries enforce the 16 KiB nested limit and duplicate-field rejection",
                "duplicate-field rejection",
                "exact-ML XOR",
                "strict full-event deserialization",
                "neither repairs nor fabricates diagnostic evidence",
                "TRANSACTION_SCORED_EVENT_CONSUMER_INVENTORY_REVIEW_REQUIRED",
                "There is no parallel v1 runtime, compatibility parser, automatic conversion, or automatic deletion path",
                "Current-version acceptance and cutover",
                "rollback to a v1-only reader is not a compatibility strategy",
                "Device Risk remains a declared optional identity only"
        );
    }

    @Test
    void docsDoNotContainRolloutOverclaims() throws Exception {
        assertThat(EngineIntelligenceSourceScanSupport.read(
                "docs/architecture/engine_intelligence_consumer_readiness.md"
        )).doesNotContainIgnoringCase(
                "production emission enabled",
                "alert projection ready",
                "ui ready",
                "analyst console ready",
                "final decision source",
                "automatic decline",
                "automatic approve"
        );
    }
}
