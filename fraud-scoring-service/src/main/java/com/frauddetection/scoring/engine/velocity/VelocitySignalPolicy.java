package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.events.reason.ReasonCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class VelocitySignalPolicy {
    static final double TRANSACTION_VELOCITY_PER_MINUTE_THRESHOLD = 5.0d;
    static final int RAPID_TRANSFER_MIN_COUNT = 2;
    static final BigDecimal RAPID_TRANSFER_PLN_THRESHOLD = FraudFeatureThresholdContract.RAPID_TRANSFER_PLN_THRESHOLD;
    static final int HIGH_VELOCITY_TRANSACTION_COUNT = FraudFeatureThresholdContract.HIGH_VELOCITY_TRANSACTION_COUNT;

    private VelocitySignalPolicy() {
    }

    static VelocityDecision decide(VelocityFacts facts) {
        boolean countSpike = facts.recentTransactionCount() >= HIGH_VELOCITY_TRANSACTION_COUNT;
        boolean highRate = facts.transactionVelocityPerMinute() >= TRANSACTION_VELOCITY_PER_MINUTE_THRESHOLD;
        boolean highAmount = facts.recentAmountSumPln().compareTo(RAPID_TRANSFER_PLN_THRESHOLD) >= 0;
        boolean rapidBurst = facts.rapidTransferFraudCaseCandidate();

        double score;
        if (rapidBurst && (countSpike || highRate)) {
            score = 0.95d;
        } else if (rapidBurst) {
            score = 0.80d;
        } else if (countSpike && highRate) {
            score = 0.75d;
        } else if (highAmount && (countSpike || highRate)) {
            score = 0.70d;
        } else if (countSpike || highRate || highAmount) {
            score = 0.50d;
        } else {
            score = 0.10d;
        }

        return new VelocityDecision(
                score,
                riskLevel(score),
                reasonCodes(rapidBurst, highRate, countSpike, highAmount),
                rapidBurst,
                highRate,
                countSpike,
                highAmount
        );
    }

    private static RiskLevel riskLevel(double score) {
        if (score >= 0.90d) {
            return RiskLevel.CRITICAL;
        }
        if (score >= 0.75d) {
            return RiskLevel.HIGH;
        }
        if (score >= 0.45d) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private static List<String> reasonCodes(
            boolean rapidBurst,
            boolean highRate,
            boolean countSpike,
            boolean highAmount
    ) {
        List<String> reasons = new ArrayList<>();
        if (rapidBurst) {
            reasons.add(ReasonCode.RAPID_PLN_20K_BURST.wireValue());
        }
        if (highRate) {
            reasons.add(ReasonCode.TRANSACTION_VELOCITY.wireValue());
        }
        if (countSpike) {
            reasons.add(ReasonCode.RECENT_TRANSACTION_SPIKE.wireValue());
        }
        if (highAmount) {
            reasons.add(ReasonCode.RECENT_AMOUNT_ACCUMULATION.wireValue());
        }
        return List.copyOf(reasons);
    }

    record VelocityDecision(
            double score,
            RiskLevel riskLevel,
            List<String> reasonCodes,
            boolean rapidBurst,
            boolean highRate,
            boolean countSpike,
            boolean highAmount
    ) {
    }

    record VelocityFacts(
            int recentTransactionCount,
            BigDecimal recentAmountSumPln,
            double transactionVelocityPerMinute,
            boolean rapidTransferFraudCaseCandidate
    ) {
    }
}
