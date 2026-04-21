package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.FraudCaseTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FraudCaseManagementService {

    private static final String SUSPICION_TYPE = "RAPID_TRANSFER_BURST_20K_PLN";
    private static final BigDecimal DEFAULT_THRESHOLD_PLN = BigDecimal.valueOf(20_000);

    private final FraudCaseRepository fraudCaseRepository;
    private final ScoredTransactionRepository scoredTransactionRepository;

    public FraudCaseManagementService(FraudCaseRepository fraudCaseRepository, ScoredTransactionRepository scoredTransactionRepository) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.scoredTransactionRepository = scoredTransactionRepository;
    }

    public void handleScoredTransaction(TransactionScoredEvent event) {
        if (event.featureSnapshot() == null
                || !Boolean.TRUE.equals(event.featureSnapshot().get("rapidTransferFraudCaseCandidate"))) {
            return;
        }

        List<String> transactionIds = transactionIds(event.featureSnapshot(), event.transactionId());
        String firstTransactionId = transactionIds.isEmpty() ? event.transactionId() : transactionIds.get(0);
        String caseKey = event.customerId() + ":" + SUSPICION_TYPE + ":" + firstTransactionId;
        FraudCaseDocument document = fraudCaseRepository.findByCaseKey(caseKey).orElseGet(() -> newCase(caseKey, event));

        LinkedHashSet<String> mergedIds = new LinkedHashSet<>(document.getTransactionIds() == null ? List.of() : document.getTransactionIds());
        mergedIds.addAll(transactionIds);
        document.setTransactionIds(List.copyOf(mergedIds));
        document.setTotalAmountPln(decimal(event.featureSnapshot().get("rapidTransferTotalPln"), DEFAULT_THRESHOLD_PLN));
        document.setThresholdPln(decimal(event.featureSnapshot().get("rapidTransferThresholdPln"), DEFAULT_THRESHOLD_PLN));
        document.setAggregationWindow(String.valueOf(event.featureSnapshot().getOrDefault("rapidTransferWindow", "PT1M")));
        document.setUpdatedAt(Instant.now());

        List<FraudCaseTransactionDocument> transactions = caseTransactions(document.getTransactionIds(), event);
        document.setTransactions(transactions);
        document.setFirstTransactionAt(firstTransactionAt(transactions, event.transactionTimestamp()));
        document.setLastTransactionAt(lastTransactionAt(transactions, event.transactionTimestamp()));

        fraudCaseRepository.save(document);
    }

    public List<FraudCaseDocument> listCases() {
        return fraudCaseRepository.findAll().stream()
                .map(this::refreshTransactionDetails)
                .toList();
    }

    public Page<FraudCaseDocument> listCases(Pageable pageable) {
        return fraudCaseRepository.findAll(pageable)
                .map(this::refreshTransactionDetails);
    }

    public FraudCaseDocument getCase(String caseId) {
        return refreshTransactionDetails(fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new com.frauddetection.alert.exception.AlertNotFoundException(caseId)));
    }

    public FraudCaseDocument updateCase(String caseId, UpdateFraudCaseRequest request) {
        FraudCaseDocument document = getCase(caseId);
        Instant now = Instant.now();
        document.setStatus(request.status());
        document.setAnalystId(request.analystId());
        document.setDecisionReason(request.decisionReason());
        document.setDecisionTags(request.tags() == null ? List.of() : List.copyOf(request.tags()));
        document.setDecidedAt(now);
        document.setUpdatedAt(now);
        return fraudCaseRepository.save(document);
    }

    private FraudCaseDocument newCase(String caseKey, TransactionScoredEvent event) {
        Instant now = Instant.now();
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(UUID.randomUUID().toString());
        document.setCaseKey(caseKey);
        document.setCustomerId(event.customerId());
        document.setSuspicionType(SUSPICION_TYPE);
        document.setStatus(FraudCaseStatus.OPEN);
        document.setReason("Multiple transfers exceeded 20000 PLN equivalent inside a short time window.");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        document.setFirstTransactionAt(event.transactionTimestamp());
        document.setTransactions(List.of());
        document.setTransactionIds(List.of());
        return document;
    }

    private FraudCaseTransactionDocument toCaseTransaction(TransactionScoredEvent event) {
        FraudCaseTransactionDocument document = new FraudCaseTransactionDocument();
        document.setTransactionId(event.transactionId());
        document.setCorrelationId(event.correlationId());
        document.setTransactionTimestamp(event.transactionTimestamp());
        document.setTransactionAmount(event.transactionAmount());
        document.setAmountPln(decimal(event.featureSnapshot().get("currentTransactionAmountPln"), BigDecimal.ZERO));
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
        document.setAmountPln(toPln(scoredTransaction.getTransactionAmount()));
        document.setFraudScore(scoredTransaction.getFraudScore());
        document.setRiskLevel(scoredTransaction.getRiskLevel());
        return document;
    }

    private FraudCaseDocument refreshTransactionDetails(FraudCaseDocument document) {
        List<String> transactionIds = document.getTransactionIds() == null ? List.of() : document.getTransactionIds();
        if (transactionIds.isEmpty()) {
            return document;
        }

        List<String> existingIds = document.getTransactions() == null
                ? List.of()
                : document.getTransactions().stream().map(FraudCaseTransactionDocument::getTransactionId).toList();
        if (existingIds.size() == transactionIds.size() && existingIds.containsAll(transactionIds)) {
            return document;
        }

        List<FraudCaseTransactionDocument> transactions = caseTransactions(transactionIds, null);
        if (transactions.isEmpty()) {
            return document;
        }

        document.setTransactions(transactions);
        document.setFirstTransactionAt(firstTransactionAt(transactions, document.getFirstTransactionAt()));
        document.setLastTransactionAt(lastTransactionAt(transactions, document.getLastTransactionAt()));
        document.setUpdatedAt(Instant.now());
        return fraudCaseRepository.save(document);
    }

    private List<FraudCaseTransactionDocument> caseTransactions(List<String> transactionIds, TransactionScoredEvent currentEvent) {
        Map<String, ScoredTransactionDocument> storedTransactions = scoredTransactionRepository.findAllById(transactionIds)
                .stream()
                .collect(Collectors.toMap(ScoredTransactionDocument::getTransactionId, transaction -> transaction));

        List<FraudCaseTransactionDocument> transactions = new ArrayList<>();
        for (String transactionId : transactionIds) {
            if (currentEvent != null && currentEvent.transactionId().equals(transactionId)) {
                transactions.add(toCaseTransaction(currentEvent));
            } else if (storedTransactions.containsKey(transactionId)) {
                transactions.add(toCaseTransaction(storedTransactions.get(transactionId)));
            }
        }
        return List.copyOf(transactions);
    }

    private Instant firstTransactionAt(List<FraudCaseTransactionDocument> transactions, Instant fallback) {
        return transactions.stream()
                .map(FraudCaseTransactionDocument::getTransactionTimestamp)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(fallback);
    }

    private Instant lastTransactionAt(List<FraudCaseTransactionDocument> transactions, Instant fallback) {
        return transactions.stream()
                .map(FraudCaseTransactionDocument::getTransactionTimestamp)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(fallback);
    }

    private List<String> transactionIds(Map<String, Object> featureSnapshot, String currentTransactionId) {
        Object value = featureSnapshot.get("rapidTransferTransactionIds");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(currentTransactionId);
    }

    private BigDecimal decimal(Object value, BigDecimal defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private BigDecimal toPln(com.frauddetection.common.events.model.Money money) {
        if (money == null || money.amount() == null) {
            return BigDecimal.ZERO;
        }
        String currency = money.currency() == null ? "PLN" : money.currency().toUpperCase(Locale.ROOT);
        BigDecimal rate = switch (currency) {
            case "EUR" -> BigDecimal.valueOf(4.30d);
            case "USD" -> BigDecimal.valueOf(4.00d);
            case "GBP" -> BigDecimal.valueOf(5.00d);
            default -> BigDecimal.ONE;
        };
        return money.amount().multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
