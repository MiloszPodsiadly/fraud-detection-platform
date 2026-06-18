package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.api.FraudCaseEvidenceTimelineResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventType;
import com.frauddetection.alert.api.FraudCaseTimelineLinkedEntityType;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.suspicious.api.SuspiciousTransactionSummaryResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReadAccessResultCountExtractorTest {

    private final ReadAccessResultCountExtractor extractor = new ReadAccessResultCountExtractor();

    @Test
    void shouldCountSummaryAsOneAggregateReadResponse() {
        int resultCount = extractor.resultCount(
                new FraudCaseWorkQueueSummaryResponse(46L, Instant.parse("2026-05-12T10:00:00Z")),
                ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE_SUMMARY
        );

        assertThat(resultCount).isEqualTo(1);
    }

    @Test
    void shouldCountSuspiciousTransactionSummaryAsOneAggregateReadResponse() {
        int resultCount = extractor.resultCount(
                SuspiciousTransactionSummaryResponse.fresh(
                        98L,
                        Instant.parse("2026-05-19T10:00:00Z"),
                        Instant.parse("2026-05-19T10:00:30Z")
                ),
                ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SUMMARY
        );

        assertThat(resultCount).isEqualTo(1);
    }

    @Test
    void shouldCountFraudCaseEvidenceTimelineEvents() {
        int resultCount = extractor.resultCount(
                new FraudCaseEvidenceTimelineResponse(
                        "case-1",
                        java.util.List.of(new FraudCaseTimelineEventResponse(
                                "FRAUD_CASE_CREATED_000001",
                                FraudCaseTimelineEventType.FRAUD_CASE_CREATED,
                                Instant.parse("2026-05-12T10:00:00Z"),
                                EvidenceSource.ALERT_SERVICE,
                                EvidenceStatus.AVAILABLE,
                                "Fraud case created",
                                "Read-only timeline event derived from existing fraud-case read data.",
                                FraudCaseTimelineLinkedEntityType.FRAUD_CASE,
                                false
                        )),
                        false,
                        false,
                        false,
                        null,
                        Instant.parse("2026-05-12T10:00:00Z")
                ),
                ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE
        );

        assertThat(resultCount).isEqualTo(1);
    }

    @Test
    void shouldCountOnlyAvailableEngineIntelligenceProjection() {
        assertThat(extractor.resultCount(
                EngineIntelligenceReadModel.notProjected("txn-old"),
                ReadAccessEndpointCategory.ENGINE_INTELLIGENCE_READ
        )).isZero();
    }

    @Test
    void shouldCountScoredTransactionDetailAsOneRead() {
        assertThat(extractor.resultCount(new Object(), ReadAccessEndpointCategory.SCORED_TRANSACTION_DETAIL))
                .isEqualTo(1);
    }
}
