package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class SuspiciousTransactionTestSupport {

    static final Instant NOW = Instant.parse("2026-05-18T10:00:00Z");
    static final Instant LATER = Instant.parse("2026-05-18T10:05:00Z");

    private SuspiciousTransactionTestSupport() {
    }

    static SuspiciousTransactionProjectionService service(SuspiciousTransactionRepository repository, AlertServiceMetrics metrics) {
        return new SuspiciousTransactionProjectionService(
                repository,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    static AlertServiceMetrics metrics(SimpleMeterRegistry registry) {
        return new AlertServiceMetrics(registry);
    }

    static SuspiciousTransactionRepository inMemoryRepository() {
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        Map<String, SuspiciousTransactionDocument> rows = new HashMap<>();
        when(repository.findByTransactionIdAndSourceEventId(any(), any())).thenAnswer(invocation -> {
            String key = key(invocation.getArgument(0), invocation.getArgument(1));
            return Optional.ofNullable(rows.get(key));
        });
        when(repository.save(any(SuspiciousTransactionDocument.class))).thenAnswer(invocation -> {
            SuspiciousTransactionDocument document = invocation.getArgument(0);
            rows.put(key(document.getTransactionId(), document.getSourceEventId()), document);
            return document;
        });
        return repository;
    }

    static TransactionScoredEvent alertWorthyEvent() {
        return event("event-1", "txn-1", true, RiskLevel.HIGH, List.of(availableEvidence()));
    }

    static TransactionScoredEvent event(
            String eventId,
            String transactionId,
            Boolean alertRecommended,
            RiskLevel riskLevel,
            List<ScoringEvidenceItem> scoringEvidence
    ) {
        TransactionScoredEvent base = TransactionFixtures.scoredTransaction().build();
        return new TransactionScoredEvent(
                eventId,
                transactionId,
                base.correlationId(),
                base.customerId(),
                base.accountId(),
                base.createdAt(),
                base.transactionTimestamp(),
                base.transactionAmount(),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                base.fraudScore(),
                riskLevel,
                base.scoringStrategy(),
                base.modelName(),
                base.modelVersion(),
                base.inferenceTimestamp(),
                base.reasonCodes(),
                base.scoreDetails(),
                base.featureSnapshot(),
                alertRecommended,
                scoringEvidence
        );
    }

    static TransactionScoredEvent withScoreDetails(TransactionScoredEvent base, Map<String, Object> scoreDetails) {
        return new TransactionScoredEvent(
                base.eventId(),
                base.transactionId(),
                base.correlationId(),
                base.customerId(),
                base.accountId(),
                base.createdAt(),
                base.transactionTimestamp(),
                base.transactionAmount(),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                base.fraudScore(),
                base.riskLevel(),
                base.scoringStrategy(),
                base.modelName(),
                base.modelVersion(),
                base.inferenceTimestamp(),
                base.reasonCodes(),
                scoreDetails,
                base.featureSnapshot(),
                base.alertRecommended(),
                base.scoringEvidence()
        );
    }

    static ScoringEvidenceItem availableEvidence() {
        return evidence(ScoringEvidenceStatus.AVAILABLE, ScoringEvidenceSource.RULE_BASED_SCORING);
    }

    static ScoringEvidenceItem partialEvidence() {
        return evidence(ScoringEvidenceStatus.PARTIAL, ScoringEvidenceSource.RULE_BASED_SCORING);
    }

    static ScoringEvidenceItem unavailableEvidence() {
        return evidence(ScoringEvidenceStatus.UNAVAILABLE, ScoringEvidenceSource.RULE_BASED_SCORING);
    }

    static ScoringEvidenceItem errorEvidence() {
        return evidence(ScoringEvidenceStatus.ERROR, ScoringEvidenceSource.RULE_BASED_SCORING);
    }

    static ScoringEvidenceItem mlEvidence() {
        return evidence(ScoringEvidenceStatus.AVAILABLE, ScoringEvidenceSource.ML_MODEL);
    }

    private static ScoringEvidenceItem evidence(ScoringEvidenceStatus status, ScoringEvidenceSource source) {
        return new ScoringEvidenceItem(
                "evidence-1",
                status == ScoringEvidenceStatus.AVAILABLE ? "HIGH_AMOUNT" : null,
                status == ScoringEvidenceStatus.AVAILABLE ? ScoringEvidenceType.RULE_MATCH : ScoringEvidenceType.DIAGNOSTIC,
                source,
                status,
                ScoringEvidenceSeverity.HIGH,
                "Evidence",
                "Bounded scoring evidence.",
                null,
                null,
                status == ScoringEvidenceStatus.AVAILABLE
                        ? Map.of()
                        : Map.of("diagnostic", true, "supportedEvidenceCreated", false, "reasonCodeApplicable", false),
                Instant.parse("2026-05-18T09:59:00Z")
        );
    }

    private static String key(String transactionId, String sourceEventId) {
        return transactionId + "|" + sourceEventId;
    }
}
