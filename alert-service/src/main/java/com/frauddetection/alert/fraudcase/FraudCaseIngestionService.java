package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FraudCaseIngestionService {

    private static final String SUSPICION_TYPE = "RAPID_TRANSFER_BURST_20K_PLN";

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudCaseTransactionAssembler transactionAssembler;

    public FraudCaseIngestionService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseTransactionAssembler transactionAssembler
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.transactionAssembler = transactionAssembler;
    }

    public void handleScoredTransaction(TransactionScoredEvent event) {
        Map<String, Object> snapshot = event.featureSnapshot() == null ? Map.of() : event.featureSnapshot();
        if (!FraudFeatureThresholdContract.isRapidTransferPlnBurst(snapshot)) {
            return;
        }

        List<String> transactionIds = transactionIds(snapshot, event.transactionId());
        String firstTransactionId = transactionIds.isEmpty() ? event.transactionId() : transactionIds.get(0);
        String caseKey = event.customerId() + ":" + SUSPICION_TYPE + ":" + firstTransactionId;
        FraudCaseDocument document = fraudCaseRepository.findByCaseKey(caseKey)
                .orElseGet(() -> newCase(caseKey, event));

        LinkedHashSet<String> mergedIds = new LinkedHashSet<>(
                document.getTransactionIds() == null ? List.of() : document.getTransactionIds()
        );
        mergedIds.addAll(transactionIds);
        document.setTransactionIds(List.copyOf(mergedIds));
        document.setTotalAmountPln(decimal(
                snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN),
                FraudFeatureThresholdContract.RAPID_TRANSFER_PLN_THRESHOLD
        ));
        document.setThresholdPln(FraudFeatureThresholdContract.RAPID_TRANSFER_PLN_THRESHOLD);
        document.setAggregationWindow(String.valueOf(
                snapshot.getOrDefault(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M")
        ));
        document.setUpdatedAt(Instant.now());

        FraudCaseTransactionAssembler.Assembly assembly = transactionAssembler.assemble(
                document.getTransactionIds(),
                event
        );
        document.setTransactions(assembly.transactions());
        document.setFirstTransactionAt(assembly.firstTransactionAt());
        document.setLastTransactionAt(assembly.lastTransactionAt());
        fraudCaseRepository.save(document);
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

    private List<String> transactionIds(Map<String, Object> featureSnapshot, String currentTransactionId) {
        Object value = featureSnapshot.get(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS);
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
}
