package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.FraudCaseTransactionDocument;
import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (!transactionIds.contains(event.transactionId())) {
            throw new FraudCaseEvidenceValidationException(
                    FraudCaseEvidenceValidationException.Reason.CURRENT_EVENT_NOT_IN_EVIDENCE_IDS
            );
        }
        String firstTransactionId = transactionIds.isEmpty() ? event.transactionId() : transactionIds.get(0);
        String caseKey = event.customerId() + ":" + SUSPICION_TYPE + ":" + firstTransactionId;
        FraudCaseDocument document = fraudCaseRepository.findByCaseKey(caseKey)
                .orElseGet(() -> newCase(caseKey, event));
        validateCaseOwnership(document, caseKey, event.customerId());

        LinkedHashSet<String> mergedIds = new LinkedHashSet<>(
                document.getTransactionIds() == null ? List.of() : document.getTransactionIds()
        );
        mergedIds.addAll(transactionIds);
        List<String> mergedTransactionIds = List.copyOf(mergedIds);
        boolean membershipChanged = !mergedTransactionIds.equals(
                document.getTransactionIds() == null ? List.of() : document.getTransactionIds()
        );
        boolean newerAggregationSnapshot = shouldApplyAggregationSnapshot(document, event);
        FraudCaseTransactionAssembler.Assembly assembly = transactionAssembler.assemble(
                mergedTransactionIds,
                event.customerId(),
                event
        );
        boolean materializationChanged = !sameTransactions(document.getTransactions(), assembly.transactions())
                || !Objects.equals(document.getFirstTransactionAt(), assembly.firstTransactionAt())
                || !Objects.equals(document.getLastTransactionAt(), assembly.lastTransactionAt());
        if (!membershipChanged && !newerAggregationSnapshot && !materializationChanged) {
            return;
        }

        if (membershipChanged) {
            document.setTransactionIds(mergedTransactionIds);
        }
        if (newerAggregationSnapshot) {
            applyAggregationSnapshot(document, event, snapshot);
        }
        document.setThresholdPln(FraudFeatureThresholdContract.RAPID_TRANSFER_PLN_THRESHOLD);
        document.setUpdatedAt(Instant.now());
        if (materializationChanged) {
            document.setTransactions(assembly.transactions());
            document.setFirstTransactionAt(assembly.firstTransactionAt());
            document.setLastTransactionAt(assembly.lastTransactionAt());
        }
        fraudCaseRepository.save(document);
    }

    private boolean shouldApplyAggregationSnapshot(FraudCaseDocument document, TransactionScoredEvent event) {
        ScoringOccurrenceOwnership candidate = ownership(event);
        if (aggregationOwnershipAbsent(document)) {
            if (document.getTotalAmountPln() != null || document.getAggregationWindow() != null) {
                throw new IllegalStateException("FRAUD_CASE_AGGREGATION_OCCURRENCE_IDENTITY_REQUIRED");
            }
            return true;
        }
        ScoringOccurrenceOwnership current;
        try {
            current = ScoringOccurrenceOwnership.fromPersistedIdentity(
                    document.getAggregationSourceEventId(),
                    document.getAggregationSourceEventCreatedAt(),
                    document.getAggregationSourceEventCreatedAtEpochSecond(),
                    document.getAggregationSourceEventCreatedAtNano(),
                    document.getAggregationSourceEventFingerprint()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("FRAUD_CASE_AGGREGATION_OCCURRENCE_IDENTITY_INVALID", exception);
        }
        if (candidate.sourceEventId().equals(current.sourceEventId())) {
            if (!candidate.sourceEventFingerprint().equals(current.sourceEventFingerprint())) {
                throw new IllegalStateException("FRAUD_CASE_AGGREGATION_SOURCE_PAYLOAD_CONFLICT");
            }
            return false;
        }
        int timestampOrder = candidate.sourceEventCreatedAt().compareTo(current.sourceEventCreatedAt());
        if (timestampOrder != 0) {
            return timestampOrder > 0;
        }
        return candidate.sourceEventId().compareTo(current.sourceEventId()) > 0;
    }

    private void applyAggregationSnapshot(
            FraudCaseDocument document,
            TransactionScoredEvent event,
            Map<String, Object> snapshot
    ) {
        ScoringOccurrenceOwnership ownership = ownership(event);
        document.setTotalAmountPln((BigDecimal) snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN));
        document.setAggregationWindow((String) snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW));
        document.setAggregationSourceEventId(ownership.sourceEventId());
        document.setAggregationSourceEventCreatedAt(ownership.sourceEventCreatedAt().toString());
        document.setAggregationSourceEventCreatedAtEpochSecond(ownership.sourceEventCreatedAt().getEpochSecond());
        document.setAggregationSourceEventCreatedAtNano(ownership.sourceEventCreatedAt().getNano());
        document.setAggregationSourceEventFingerprint(ownership.sourceEventFingerprint());
    }

    private ScoringOccurrenceOwnership ownership(TransactionScoredEvent event) {
        return ScoringOccurrenceOwnership.authoritative(
                event.eventId(),
                event.createdAt(),
                ScoringOccurrenceFingerprint.from(event)
        );
    }

    private boolean aggregationOwnershipAbsent(FraudCaseDocument document) {
        return document.getAggregationSourceEventId() == null
                && document.getAggregationSourceEventCreatedAt() == null
                && document.getAggregationSourceEventCreatedAtEpochSecond() == null
                && document.getAggregationSourceEventCreatedAtNano() == null
                && document.getAggregationSourceEventFingerprint() == null;
    }

    private boolean sameTransactions(
            List<FraudCaseTransactionDocument> current,
            List<FraudCaseTransactionDocument> candidate
    ) {
        List<FraudCaseTransactionDocument> currentTransactions = current == null ? List.of() : current;
        if (currentTransactions.size() != candidate.size()) {
            return false;
        }
        for (int index = 0; index < currentTransactions.size(); index++) {
            var left = currentTransactions.get(index);
            var right = candidate.get(index);
            if (!Objects.equals(left.getTransactionId(), right.getTransactionId())
                    || !Objects.equals(left.getCorrelationId(), right.getCorrelationId())
                    || !Objects.equals(left.getTransactionTimestamp(), right.getTransactionTimestamp())
                    || !Objects.equals(left.getTransactionAmount(), right.getTransactionAmount())
                    || !Objects.equals(left.getAmountPln(), right.getAmountPln())
                    || !Objects.equals(left.getFraudScore(), right.getFraudScore())
                    || !Objects.equals(left.getRiskLevel(), right.getRiskLevel())) {
                return false;
            }
        }
        return true;
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

    private void validateCaseOwnership(FraudCaseDocument document, String expectedCaseKey, String expectedCustomerId) {
        if (!Objects.equals(expectedCaseKey, document.getCaseKey())) {
            throw new FraudCaseEvidenceValidationException(
                    FraudCaseEvidenceValidationException.Reason.CASE_KEY_MISMATCH
            );
        }
        if (!Objects.equals(expectedCustomerId, document.getCustomerId())) {
            throw new FraudCaseEvidenceValidationException(
                    FraudCaseEvidenceValidationException.Reason.CASE_CUSTOMER_MISMATCH
            );
        }
    }

    private List<String> transactionIds(Map<String, Object> featureSnapshot, String currentTransactionId) {
        Object value = featureSnapshot.get(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(currentTransactionId);
    }

}
