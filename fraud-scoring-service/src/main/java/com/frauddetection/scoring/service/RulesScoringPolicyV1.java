package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.events.reason.ReasonCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class RulesScoringPolicyV1 {
    static final int HIGH_VELOCITY_RECENT_TRANSACTION_COUNT_THRESHOLD = 5;
    static final double HIGH_VELOCITY_WEIGHT = 0.42d;
    static final double RECENT_AMOUNT_ACTIVITY_WEIGHT = 0.24d;
    static final double RAPID_PLN_20K_BURST_WEIGHT = 0.65d;

    private RulesScoringPolicyV1() {
    }

    static double applySemanticVelocityAndAmountRules(
            TransactionEnrichedEvent event,
            double currentScore,
            Set<String> reasonCodes,
            Map<String, Object> scoreDetails
    ) {
        double score = currentScore;
        if (isHighVelocity(event)) {
            score += HIGH_VELOCITY_WEIGHT;
            reasonCodes.add(ReasonCode.HIGH_VELOCITY.wireValue());
            scoreDetails.put("highVelocityRulesV1Weight", HIGH_VELOCITY_WEIGHT);
        }
        if (isRecentAmountActivity(event)) {
            score += RECENT_AMOUNT_ACTIVITY_WEIGHT;
            reasonCodes.add(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
            scoreDetails.put("recentAmountActivityRulesV1Weight", RECENT_AMOUNT_ACTIVITY_WEIGHT);
        }
        if (isRapidPln20kBurst(event)) {
            score += RAPID_PLN_20K_BURST_WEIGHT;
            reasonCodes.add(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
            scoreDetails.put("rapidPln20kBurstRulesV1Weight", RAPID_PLN_20K_BURST_WEIGHT);
        }
        return score;
    }

    static boolean isHighVelocity(TransactionEnrichedEvent event) {
        Optional<Integer> canonicalCount = canonicalInteger(event.featureSnapshot(), FraudFeatureContract.RECENT_TRANSACTION_COUNT);
        if (canonicalCount.isPresent()) {
            return canonicalCount.get() >= HIGH_VELOCITY_RECENT_TRANSACTION_COUNT_THRESHOLD;
        }
        if (isPresentButInvalid(event.featureSnapshot(), FraudFeatureContract.RECENT_TRANSACTION_COUNT, Integer.class)) {
            return false;
        }
        if (event.recentTransactionCount() != null) {
            return event.recentTransactionCount() >= HIGH_VELOCITY_RECENT_TRANSACTION_COUNT_THRESHOLD;
        }
        return containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_HIGH_VELOCITY);
    }

    static boolean isRecentAmountActivity(TransactionEnrichedEvent event) {
        PredicateResolution resolved = recentAmountActivity(event);
        return switch (resolved) {
            case TRUE -> true;
            case FALSE -> false;
            case ABSENT -> containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY);
        };
    }

    static boolean isRapidPln20kBurst(TransactionEnrichedEvent event) {
        PredicateResolution canonicalBurst = canonicalRapidBurst(event);
        if (canonicalBurst != PredicateResolution.ABSENT) {
            return canonicalBurst == PredicateResolution.TRUE;
        }
        if (event.featureSnapshot() != null
                && event.featureSnapshot().containsKey(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE)) {
            return Boolean.TRUE.equals(event.featureSnapshot().get(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE));
        }
        return containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST);
    }

    private static PredicateResolution recentAmountActivity(TransactionEnrichedEvent event) {
        Map<String, Object> snapshot = event.featureSnapshot();
        if (isPresentButInvalid(snapshot, FraudFeatureContract.RECENT_TRANSACTION_COUNT, Integer.class)
                || isPresentButInvalid(snapshot, FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, BigDecimal.class)) {
            return PredicateResolution.FALSE;
        }
        Integer count = canonicalInteger(snapshot, FraudFeatureContract.RECENT_TRANSACTION_COUNT)
                .orElse(event.recentTransactionCount());
        BigDecimal amount = canonicalDecimal(snapshot, FraudFeatureContract.RECENT_AMOUNT_SUM_PLN)
                .orElse(safeTopLevelRecentAmountPln(event).orElse(null));
        if (count == null || amount == null) {
            return PredicateResolution.ABSENT;
        }
        return amount.compareTo(BigDecimal.valueOf(5000)) >= 0 && count >= 2
                ? PredicateResolution.TRUE
                : PredicateResolution.FALSE;
    }

    private static PredicateResolution canonicalRapidBurst(TransactionEnrichedEvent event) {
        Map<String, Object> snapshot = event.featureSnapshot();
        PredicateResolution rapidPair = rapidPairBurst(snapshot);
        if (rapidPair != PredicateResolution.ABSENT) {
            return rapidPair;
        }
        return recentFactsRapidBurst(event);
    }

    private static PredicateResolution rapidPairBurst(Map<String, Object> snapshot) {
        if (snapshot == null
                || (!snapshot.containsKey(FraudFeatureContract.RAPID_TRANSFER_COUNT)
                && !snapshot.containsKey(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN))) {
            return PredicateResolution.ABSENT;
        }
        if (isPresentButInvalid(snapshot, FraudFeatureContract.RAPID_TRANSFER_COUNT, Integer.class)
                || isPresentButInvalid(snapshot, FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, BigDecimal.class)) {
            return PredicateResolution.FALSE;
        }
        Integer count = canonicalInteger(snapshot, FraudFeatureContract.RAPID_TRANSFER_COUNT).orElse(null);
        BigDecimal amount = canonicalDecimal(snapshot, FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN).orElse(null);
        if (count == null || amount == null) {
            return PredicateResolution.ABSENT;
        }
        return rapidPredicate(count, amount);
    }

    private static PredicateResolution recentFactsRapidBurst(TransactionEnrichedEvent event) {
        Map<String, Object> snapshot = event.featureSnapshot();
        if (isPresentButInvalid(snapshot, FraudFeatureContract.RECENT_TRANSACTION_COUNT, Integer.class)
                || isPresentButInvalid(snapshot, FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, BigDecimal.class)) {
            return PredicateResolution.FALSE;
        }
        Integer count = canonicalInteger(snapshot, FraudFeatureContract.RECENT_TRANSACTION_COUNT)
                .orElse(event.recentTransactionCount());
        BigDecimal amount = canonicalDecimal(snapshot, FraudFeatureContract.RECENT_AMOUNT_SUM_PLN)
                .orElse(safeTopLevelRecentAmountPln(event).orElse(null));
        if (count == null || amount == null) {
            return PredicateResolution.ABSENT;
        }
        return rapidPredicate(count, amount);
    }

    private static PredicateResolution rapidPredicate(int count, BigDecimal amount) {
        try {
            return FraudFeatureThresholdContract.isRapidTransferPlnBurst(count, amount)
                    ? PredicateResolution.TRUE
                    : PredicateResolution.FALSE;
        } catch (IllegalArgumentException exception) {
            return PredicateResolution.FALSE;
        }
    }

    private static Optional<BigDecimal> safeTopLevelRecentAmountPln(TransactionEnrichedEvent event) {
        if (event.recentAmountSum() == null || !"PLN".equalsIgnoreCase(event.recentAmountSum().currency())) {
            return Optional.empty();
        }
        return Optional.ofNullable(event.recentAmountSum().amount());
    }

    private static Optional<Integer> canonicalInteger(Map<String, Object> snapshot, String key) {
        if (snapshot == null || !snapshot.containsKey(key)) {
            return Optional.empty();
        }
        Object value = snapshot.get(key);
        return value instanceof Integer integer ? Optional.of(integer) : Optional.empty();
    }

    private static Optional<BigDecimal> canonicalDecimal(Map<String, Object> snapshot, String key) {
        if (snapshot == null || !snapshot.containsKey(key)) {
            return Optional.empty();
        }
        Object value = snapshot.get(key);
        return value instanceof BigDecimal decimal ? Optional.of(decimal) : Optional.empty();
    }

    private static boolean isPresentButInvalid(Map<String, Object> snapshot, String key, Class<?> expectedType) {
        return snapshot != null && snapshot.containsKey(key) && !expectedType.isInstance(snapshot.get(key));
    }

    private static boolean containsFeatureFlag(List<String> featureFlags, String featureFlag) {
        return featureFlags != null && featureFlags.contains(featureFlag);
    }

    private enum PredicateResolution {
        TRUE,
        FALSE,
        ABSENT
    }
}
