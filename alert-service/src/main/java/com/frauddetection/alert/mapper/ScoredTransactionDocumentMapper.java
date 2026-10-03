package com.frauddetection.alert.mapper;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

@Component
public class ScoredTransactionDocumentMapper {

    public ScoredTransactionDocument toDocument(TransactionScoredEvent event) {
        ScoringOccurrenceOwnership occurrence = ScoringOccurrenceOwnership.authoritative(
                event.eventId(),
                event.createdAt()
        );
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId(event.transactionId());
        document.setSourceEventId(occurrence.sourceEventId());
        document.setSourceEventCreatedAt(occurrence.sourceEventCreatedAt().toString());
        document.setSourceEventCreatedAtEpochSecond(occurrence.sourceEventCreatedAt().getEpochSecond());
        document.setSourceEventCreatedAtNano(occurrence.sourceEventCreatedAt().getNano());
        document.setCustomerId(event.customerId());
        document.setCorrelationId(event.correlationId());
        document.setTransactionTimestamp(event.transactionTimestamp());
        document.setScoredAt(resolveScoredAt(event));
        document.setTransactionAmount(event.transactionAmount());
        document.setMerchantInfo(event.merchantInfo());
        document.setTransactionIdSearch(normalizeSearchValue(event.transactionId()));
        document.setCustomerIdSearch(normalizeSearchValue(event.customerId()));
        document.setMerchantIdSearch(normalizeSearchValue(event.merchantInfo() == null ? null : event.merchantInfo().merchantId()));
        document.setCurrencySearch(normalizeSearchValue(event.transactionAmount() == null ? null : event.transactionAmount().currency()));
        document.setFraudScore(event.fraudScore());
        document.setRiskLevel(event.riskLevel());
        document.setAlertRecommended(event.alertRecommended());
        document.setReasonCodes(event.reasonCodes());
        document.setAnalystRecommendation(event.analystRecommendation());
        return document;
    }

    public ScoredTransaction toDomain(ScoredTransactionDocument document) {
        return new ScoredTransaction(
                document.getTransactionId(),
                document.getCustomerId(),
                document.getCorrelationId(),
                document.getTransactionTimestamp(),
                document.getScoredAt(),
                document.getTransactionAmount(),
                document.getMerchantInfo(),
                document.getFraudScore(),
                document.getRiskLevel(),
                document.getAlertRecommended(),
                document.getReasonCodes(),
                document.getAnalystRecommendation(),
                occurrence(document)
        );
    }

    private ScoringOccurrenceOwnership occurrence(ScoredTransactionDocument document) {
        if (document.getSourceEventId() == null
                || document.getSourceEventCreatedAt() == null
                || document.getSourceEventCreatedAtEpochSecond() == null
                || document.getSourceEventCreatedAtNano() == null) {
            return ScoringOccurrenceOwnership.unknown();
        }
        try {
            Instant sourceEventCreatedAt = Instant.ofEpochSecond(
                    document.getSourceEventCreatedAtEpochSecond(),
                    document.getSourceEventCreatedAtNano()
            );
            if (!sourceEventCreatedAt.equals(Instant.parse(document.getSourceEventCreatedAt()))) {
                return ScoringOccurrenceOwnership.unknown();
            }
            return ScoringOccurrenceOwnership.authoritative(
                    document.getSourceEventId(),
                    sourceEventCreatedAt
            );
        } catch (RuntimeException exception) {
            return ScoringOccurrenceOwnership.unknown();
        }
    }

    private Instant resolveScoredAt(TransactionScoredEvent event) {
        if (event.inferenceTimestamp() != null) {
            return event.inferenceTimestamp();
        }
        if (event.createdAt() != null) {
            return event.createdAt();
        }
        return Instant.now();
    }

    private String normalizeSearchValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
