package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.events.reason.ReasonCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
        if (event.recentTransactionCount() != null) {
            return event.recentTransactionCount() >= HIGH_VELOCITY_RECENT_TRANSACTION_COUNT_THRESHOLD;
        }
        return containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_HIGH_VELOCITY);
    }

    static boolean isRecentAmountActivity(TransactionEnrichedEvent event) {
        if (event.recentTransactionCount() != null
                && event.recentAmountSum() != null
                && event.recentTransactionCount() >= 2) {
            return event.recentAmountSum().amount().compareTo(BigDecimal.valueOf(5000)) >= 0;
        }
        return containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY);
    }

    static boolean isRapidPln20kBurst(TransactionEnrichedEvent event) {
        if (event.recentTransactionCount() != null && event.featureSnapshot() != null) {
            Object recentAmountSumPln = event.featureSnapshot().get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);
            if (recentAmountSumPln instanceof BigDecimal amountPln) {
                return FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                        event.recentTransactionCount(),
                        amountPln
                );
            }
        }
        if (event.featureSnapshot() != null
                && Boolean.TRUE.equals(event.featureSnapshot().get(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE))) {
            return true;
        }
        return containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST);
    }

    private static boolean containsFeatureFlag(List<String> featureFlags, String featureFlag) {
        return featureFlags != null && featureFlags.contains(featureFlag);
    }
}
