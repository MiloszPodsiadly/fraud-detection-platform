package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.reason.ReasonCode;

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
        for (RulesV1SignalResolution signal : RulesV1CompatibilityResolver.resolve(event)) {
            if (signal.contributes()) {
                score += signal.contribution().weight();
                reasonCodes.add(signal.reasonCode());
                scoreDetails.put(scoreDetailWeightKey(signal.reasonCode()), signal.contribution().weight());
                scoreDetails.put(scoreDetailSourceKey(signal.reasonCode()), signal.contribution().sources().stream()
                        .map(Enum::name)
                        .toList());
            }
        }
        return score;
    }

    static boolean isHighVelocity(TransactionEnrichedEvent event) {
        return contributes(event, ReasonCode.HIGH_VELOCITY);
    }

    static boolean isRecentAmountActivity(TransactionEnrichedEvent event) {
        return contributes(event, ReasonCode.HIGH_AMOUNT_ACTIVITY);
    }

    static boolean isRapidPln20kBurst(TransactionEnrichedEvent event) {
        return contributes(event, ReasonCode.RAPID_PLN_20K_BURST);
    }

    private static boolean contributes(TransactionEnrichedEvent event, ReasonCode reasonCode) {
        return RulesV1CompatibilityResolver.resolve(event).stream()
                .filter(signal -> signal.reasonCode().equals(reasonCode.wireValue()))
                .anyMatch(RulesV1SignalResolution::contributes);
    }

    private static String scoreDetailWeightKey(String reasonCode) {
        if (ReasonCode.HIGH_VELOCITY.wireValue().equals(reasonCode)) {
            return "highVelocityRulesV1Weight";
        }
        if (ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue().equals(reasonCode)) {
            return "recentAmountActivityRulesV1Weight";
        }
        return "rapidPln20kBurstRulesV1Weight";
    }

    private static String scoreDetailSourceKey(String reasonCode) {
        if (ReasonCode.HIGH_VELOCITY.wireValue().equals(reasonCode)) {
            return "highVelocityRulesV1Sources";
        }
        if (ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue().equals(reasonCode)) {
            return "recentAmountActivityRulesV1Sources";
        }
        return "rapidPln20kBurstRulesV1Sources";
    }
}
