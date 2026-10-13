package com.frauddetection.common.events.features;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FraudFeatureThresholdContract {
    public static final int RAPID_TRANSFER_MIN_COUNT = 2;
    public static final BigDecimal RAPID_TRANSFER_PLN_THRESHOLD = BigDecimal.valueOf(20_000);

    private FraudFeatureThresholdContract() {
    }

    public static boolean isRapidTransferPlnBurst(int transactionCount, BigDecimal amountPln) {
        Objects.requireNonNull(amountPln, "amountPln is required");
        if (!FraudFeatureValueBoundsContract.isWithinCountBounds(transactionCount)
                || !FraudFeatureValueBoundsContract.isWithinAmountBounds(amountPln)) {
            throw new IllegalArgumentException("rapid transfer facts are outside canonical bounds");
        }
        return transactionCount >= RAPID_TRANSFER_MIN_COUNT
                && amountPln.compareTo(RAPID_TRANSFER_PLN_THRESHOLD) >= 0;
    }

    public static boolean isRapidTransferPlnBurst(Map<String, Object> featureSnapshot) {
        return assessRapidTransferPlnBurst(featureSnapshot, null, false).status() == RapidTransferStatus.VALID_BURST;
    }

    public static RapidTransferAssessment assessRapidTransferPlnBurst(
            Map<String, Object> featureSnapshot,
            String currentTransactionId,
            boolean rapidTransferClaimed
    ) {
        if (featureSnapshot == null || featureSnapshot.isEmpty()) {
            return rapidTransferClaimed
                    ? RapidTransferAssessment.invalid(RapidTransferEvidenceIssue.MEMBERSHIP_MISSING)
                    : RapidTransferAssessment.notApplicable();
        }
        Integer count = integer(featureSnapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT));
        BigDecimal amountPln = decimal(featureSnapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN));
        boolean canonicalFacts = count != null
                && amountPln != null
                && FraudFeatureValueBoundsContract.isWithinCountBounds(count)
                && FraudFeatureValueBoundsContract.isWithinAmountBounds(amountPln);
        if (!canonicalFacts) {
            if (!rapidTransferClaimed) {
                return RapidTransferAssessment.notApplicable();
            }
            return membershipIssue(featureSnapshot, count, currentTransactionId)
                    .map(RapidTransferAssessment::invalid)
                    .orElseGet(() -> RapidTransferAssessment.invalid(
                            RapidTransferEvidenceIssue.SNAPSHOT_INCONSISTENT
                    ));
        }

        boolean qualifyingFacts = isRapidTransferPlnBurst(count, amountPln);
        if (!qualifyingFacts && !rapidTransferClaimed) {
            return RapidTransferAssessment.notBurst();
        }

        java.util.Optional<RapidTransferEvidenceIssue> membershipIssue =
                membershipIssue(featureSnapshot, count, currentTransactionId);
        if (membershipIssue.isPresent()) {
            return RapidTransferAssessment.invalid(membershipIssue.orElseThrow());
        }
        if (!FraudFeatureValueBoundsContract.isCanonicalRecentTransactionWindowText(
                string(featureSnapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW))
        ) || !FraudFeatureValueBoundsContract.isCanonicalRecentTransactionWindowText(
                string(featureSnapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW))
        )) {
            return RapidTransferAssessment.invalid(RapidTransferEvidenceIssue.SNAPSHOT_INCONSISTENT);
        }
        if (!qualifyingFacts) {
            return RapidTransferAssessment.invalid(RapidTransferEvidenceIssue.SNAPSHOT_INCONSISTENT);
        }
        @SuppressWarnings("unchecked")
        List<String> transactionIds = (List<String>) featureSnapshot.get(
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS
        );
        return RapidTransferAssessment.validBurst(transactionIds);
    }

    private static java.util.Optional<RapidTransferEvidenceIssue> membershipIssue(
            Map<String, Object> featureSnapshot,
            Integer expectedCount,
            String currentTransactionId
    ) {
        Object value = featureSnapshot.get(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS);
        if (value == null) {
            return java.util.Optional.of(RapidTransferEvidenceIssue.MEMBERSHIP_MISSING);
        }
        if (!(value instanceof List<?> ids)
                || ids.size() < RAPID_TRANSFER_MIN_COUNT
                || (expectedCount != null && ids.size() != expectedCount)) {
            return java.util.Optional.of(RapidTransferEvidenceIssue.MEMBERSHIP_INVALID);
        }
        HashSet<String> distinctIds = new HashSet<>();
        for (Object id : ids) {
            if (!(id instanceof String text)
                    || text.isBlank()
                    || !text.equals(text.trim())
                    || !distinctIds.add(text)) {
                return java.util.Optional.of(RapidTransferEvidenceIssue.MEMBERSHIP_INVALID);
            }
        }
        if (currentTransactionId != null && !distinctIds.contains(currentTransactionId)) {
            return java.util.Optional.of(RapidTransferEvidenceIssue.CURRENT_TRANSACTION_MISSING);
        }
        return java.util.Optional.empty();
    }

    private static Integer integer(Object value) {
        return value instanceof Integer integer ? integer : null;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : null;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    public enum RapidTransferStatus {
        NOT_APPLICABLE,
        NOT_A_BURST,
        VALID_BURST,
        INVALID_BURST_EVIDENCE
    }

    public enum RapidTransferEvidenceIssue {
        NONE,
        MEMBERSHIP_MISSING,
        MEMBERSHIP_INVALID,
        CURRENT_TRANSACTION_MISSING,
        SNAPSHOT_INCONSISTENT
    }

    public record RapidTransferAssessment(
            RapidTransferStatus status,
            RapidTransferEvidenceIssue issue,
            List<String> transactionIds
    ) {
        public RapidTransferAssessment {
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(issue, "issue is required");
            transactionIds = List.copyOf(transactionIds);
        }

        private static RapidTransferAssessment notApplicable() {
            return new RapidTransferAssessment(
                    RapidTransferStatus.NOT_APPLICABLE,
                    RapidTransferEvidenceIssue.NONE,
                    List.of()
            );
        }

        private static RapidTransferAssessment notBurst() {
            return new RapidTransferAssessment(
                    RapidTransferStatus.NOT_A_BURST,
                    RapidTransferEvidenceIssue.NONE,
                    List.of()
            );
        }

        private static RapidTransferAssessment validBurst(List<String> transactionIds) {
            return new RapidTransferAssessment(
                    RapidTransferStatus.VALID_BURST,
                    RapidTransferEvidenceIssue.NONE,
                    transactionIds
            );
        }

        private static RapidTransferAssessment invalid(RapidTransferEvidenceIssue issue) {
            return new RapidTransferAssessment(
                    RapidTransferStatus.INVALID_BURST_EVIDENCE,
                    issue,
                    List.of()
            );
        }
    }
}
