package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.mapper.CanonicalMonetaryEvidenceMapper;
import com.frauddetection.alert.persistence.FraudCaseTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class FraudCaseTransactionAssembler {

    private final ScoredTransactionRepository scoredTransactionRepository;

    public FraudCaseTransactionAssembler(ScoredTransactionRepository scoredTransactionRepository) {
        this.scoredTransactionRepository = scoredTransactionRepository;
    }

    public Assembly assemble(
            List<String> transactionIds,
            String expectedCustomerId,
            TransactionScoredEvent currentEvent
    ) {
        if (currentEvent != null && !Objects.equals(expectedCustomerId, currentEvent.customerId())) {
            throw new FraudCaseEvidenceValidationException(
                    FraudCaseEvidenceValidationException.Reason.CURRENT_EVENT_CUSTOMER_MISMATCH
            );
        }
        Map<String, ScoredTransactionDocument> storedTransactions = scoredTransactionRepository.findAllById(transactionIds)
                .stream()
                .collect(Collectors.toMap(ScoredTransactionDocument::getTransactionId, transaction -> transaction));

        storedTransactions.values().forEach(transaction -> {
            if (!Objects.equals(expectedCustomerId, transaction.getCustomerId())) {
                throw new FraudCaseEvidenceValidationException(
                        FraudCaseEvidenceValidationException.Reason.CROSS_CUSTOMER_TRANSACTION
                );
            }
        });

        List<FraudCaseTransactionDocument> transactions = new ArrayList<>();
        for (String transactionId : transactionIds) {
            if (storedTransactions.containsKey(transactionId)) {
                transactions.add(toCaseTransaction(storedTransactions.get(transactionId)));
            } else if (currentEvent != null && currentEvent.transactionId().equals(transactionId)) {
                transactions.add(toCaseTransaction(currentEvent));
            }
        }
        List<FraudCaseTransactionDocument> result = List.copyOf(transactions);
        Instant fallback = currentEvent == null ? null : currentEvent.transactionTimestamp();
        return new Assembly(
                result,
                boundary(result, fallback, true),
                boundary(result, fallback, false)
        );
    }

    private FraudCaseTransactionDocument toCaseTransaction(TransactionScoredEvent event) {
        FraudCaseTransactionDocument document = new FraudCaseTransactionDocument();
        document.setTransactionId(event.transactionId());
        document.setCorrelationId(event.correlationId());
        document.setTransactionTimestamp(event.transactionTimestamp());
        document.setTransactionAmount(event.transactionAmount());
        document.setAmountPln(CanonicalMonetaryEvidenceMapper.currentTransactionAmountPln(event.featureSnapshot()));
        document.setFraudScore(event.fraudScore());
        document.setRiskLevel(event.riskLevel());
        return document;
    }

    private FraudCaseTransactionDocument toCaseTransaction(ScoredTransactionDocument scoredTransaction) {
        FraudCaseTransactionDocument document = new FraudCaseTransactionDocument();
        document.setTransactionId(scoredTransaction.getTransactionId());
        document.setCorrelationId(scoredTransaction.getCorrelationId());
        document.setTransactionTimestamp(scoredTransaction.getTransactionTimestamp());
        document.setTransactionAmount(scoredTransaction.getTransactionAmount());
        document.setAmountPln(scoredTransaction.getCurrentTransactionAmountPln());
        document.setFraudScore(scoredTransaction.getFraudScore());
        document.setRiskLevel(scoredTransaction.getRiskLevel());
        return document;
    }

    private Instant boundary(
            List<FraudCaseTransactionDocument> transactions,
            Instant fallback,
            boolean earliest
    ) {
        return transactions.stream()
                .map(FraudCaseTransactionDocument::getTransactionTimestamp)
                .filter(Objects::nonNull)
                .reduce(earliest ? (left, right) -> left.isBefore(right) ? left : right
                        : (left, right) -> left.isAfter(right) ? left : right)
                .orElse(fallback);
    }

    public record Assembly(
            List<FraudCaseTransactionDocument> transactions,
            Instant firstTransactionAt,
            Instant lastTransactionAt
    ) {
        public Assembly {
            transactions = List.copyOf(transactions);
        }
    }
}
