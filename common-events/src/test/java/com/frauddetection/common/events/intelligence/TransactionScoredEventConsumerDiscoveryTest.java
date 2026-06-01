package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionScoredEventConsumerDiscoveryTest {

    private static final List<String> REVIEWED_PRODUCTION_REFERENCES = List.of(
            "alert-service/src/main/java/com/frauddetection/alert/config/AlertKafkaConfig.java",
            "alert-service/src/main/java/com/frauddetection/alert/evidence/AlertEvidenceSnapshotProjectionService.java",
            "alert-service/src/main/java/com/frauddetection/alert/evidence/EvidenceProjectionService.java",
            "alert-service/src/main/java/com/frauddetection/alert/mapper/ScoredTransactionDocumentMapper.java",
            "alert-service/src/main/java/com/frauddetection/alert/messaging/TransactionScoredEventListener.java",
            "alert-service/src/main/java/com/frauddetection/alert/service/AlertCaseFactory.java",
            "alert-service/src/main/java/com/frauddetection/alert/service/AlertManagementService.java",
            "alert-service/src/main/java/com/frauddetection/alert/service/AlertManagementUseCase.java",
            "alert-service/src/main/java/com/frauddetection/alert/service/FraudCaseManagementService.java",
            "alert-service/src/main/java/com/frauddetection/alert/service/TransactionMonitoringService.java",
            "alert-service/src/main/java/com/frauddetection/alert/service/TransactionMonitoringUseCase.java",
            "alert-service/src/main/java/com/frauddetection/alert/suspicious/SuspiciousTransactionProjectionService.java",
            "common-events/src/main/java/com/frauddetection/common/events/contract/TransactionScoredEvent.java",
            "common-test-support/src/main/java/com/frauddetection/common/testsupport/fixture/TransactionFixtures.java",
            "fraud-scoring-service/src/main/java/com/frauddetection/scoring/config/FraudScoringKafkaConfig.java",
            "fraud-scoring-service/src/main/java/com/frauddetection/scoring/mapper/TransactionScoredEventMapper.java",
            "fraud-scoring-service/src/main/java/com/frauddetection/scoring/messaging/KafkaTransactionScoredEventPublisher.java",
            "fraud-scoring-service/src/main/java/com/frauddetection/scoring/messaging/TransactionScoredEventPublisher.java",
            "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/TransactionFraudScoringService.java"
    );

    @Test
    void productionReferencesRemainReviewed() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.productionJavaFilesContaining("TransactionScoredEvent"))
                .withFailMessage("TRANSACTION_SCORED_EVENT_CONSUMER_INVENTORY_REVIEW_REQUIRED")
                .containsExactlyElementsOf(REVIEWED_PRODUCTION_REFERENCES);
    }

    @Test
    void docsMentionKnownConsumerPathsAndFixtureReaders() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.read(
                "docs/architecture/engine_intelligence_consumer_readiness.md"
        )).contains(
                "AlertKafkaConfig",
                "TransactionScoredEventListener",
                "TransactionMonitoringService",
                "ScoredTransactionDocumentMapper",
                "AlertManagementService",
                "FraudCaseManagementService",
                "SuspiciousTransactionProjectionService",
                "EvidenceProjectionService",
                "AlertEvidenceSnapshotProjectionService",
                "TransactionFraudScoringService",
                "TransactionScoredEventMapper",
                "KafkaTransactionScoredEventPublisher",
                "TransactionFixtures",
                "AlertServiceIntegrationTest",
                "FraudDetectionPlatformEndToEndIntegrationTest",
                "FraudScoringIntegrationTest",
                "no direct `TransactionScoredEvent` deserializer in API or analyst console UI",
                "no scored-event fixture reader was found"
        );
    }
}
