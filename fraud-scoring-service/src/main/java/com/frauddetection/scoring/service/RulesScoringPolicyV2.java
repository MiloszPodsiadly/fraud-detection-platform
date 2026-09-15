package com.frauddetection.scoring.service;

import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.evidence.ScoringEvidenceFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RulesScoringPolicyV2 {
    public static final String MODEL_NAME = "rule-based-engine";
    public static final String MODEL_VERSION = "v2";
    static final double BASE_SCORE = 0.05d;
    static final int HIGH_VELOCITY_RECENT_TRANSACTION_COUNT_THRESHOLD = 5;
    static final int MERCHANT_CONCENTRATION_THRESHOLD = 5;
    static final BigDecimal HIGH_AMOUNT_ACTIVITY_THRESHOLD_PLN = BigDecimal.valueOf(5000);
    static final BigDecimal HIGH_TRANSACTION_AMOUNT_THRESHOLD_PLN = BigDecimal.valueOf(1000);
    static final double DEVICE_NOVELTY_WEIGHT = 0.18d;
    static final double COUNTRY_MISMATCH_WEIGHT = 0.24d;
    static final double PROXY_OR_VPN_WEIGHT = 0.16d;
    static final double MERCHANT_CONCENTRATION_WEIGHT = 0.08d;
    static final double HIGH_VELOCITY_WEIGHT = 0.42d;
    static final double HIGH_AMOUNT_ACTIVITY_WEIGHT = 0.24d;
    static final double RAPID_PLN_20K_BURST_WEIGHT = 0.65d;

    private final ScoringProperties scoringProperties;
    private final ScoringEvidenceFactory scoringEvidenceFactory = new ScoringEvidenceFactory();

    public RulesScoringPolicyV2(ScoringProperties scoringProperties) {
        this.scoringProperties = Objects.requireNonNull(scoringProperties, "scoringProperties is required");
    }

    public FraudScoreResult score(RulesV2ValidatedInput input) {
        Objects.requireNonNull(input, "input is required");
        double score = BASE_SCORE;
        Set<String> reasonCodes = new LinkedHashSet<>();
        Map<String, Object> scoreDetails = new LinkedHashMap<>();

        score = addIf(input.deviceNovelty(), ReasonCode.DEVICE_NOVELTY, DEVICE_NOVELTY_WEIGHT, score, reasonCodes, scoreDetails);
        score = addIf(input.countryMismatch(), ReasonCode.COUNTRY_MISMATCH, COUNTRY_MISMATCH_WEIGHT, score, reasonCodes, scoreDetails);
        score = addIf(input.proxyOrVpnDetected(), ReasonCode.PROXY_OR_VPN, PROXY_OR_VPN_WEIGHT, score, reasonCodes, scoreDetails);
        score = addIf(
                input.merchantFrequency7d() >= MERCHANT_CONCENTRATION_THRESHOLD,
                ReasonCode.MERCHANT_CONCENTRATION,
                MERCHANT_CONCENTRATION_WEIGHT,
                score,
                reasonCodes,
                scoreDetails
        );
        score = addIf(
                input.recentTransactionCount() >= HIGH_VELOCITY_RECENT_TRANSACTION_COUNT_THRESHOLD,
                ReasonCode.HIGH_VELOCITY,
                HIGH_VELOCITY_WEIGHT,
                score,
                reasonCodes,
                scoreDetails
        );
        score = addIf(
                highAmountActivity(input),
                ReasonCode.HIGH_AMOUNT_ACTIVITY,
                HIGH_AMOUNT_ACTIVITY_WEIGHT,
                score,
                reasonCodes,
                scoreDetails
        );
        score = addIf(
                FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                        input.recentTransactionCount(),
                        input.recentAmountSumPln()
                ),
                ReasonCode.RAPID_PLN_20K_BURST,
                RAPID_PLN_20K_BURST_WEIGHT,
                score,
                reasonCodes,
                scoreDetails
        );
        if (input.currentTransactionAmountPln().compareTo(HIGH_TRANSACTION_AMOUNT_THRESHOLD_PLN) >= 0) {
            reasonCodes.add(ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue());
            scoreDetails.put("highTransactionAmountDiagnostic", true);
        }

        double cappedScore = Math.min(roundScore(score), 0.99d);
        RiskLevel riskLevel = mapRiskLevel(cappedScore);
        boolean alertRecommended = riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
        Instant inferenceTimestamp = Instant.now();
        List<ScoringEvidenceItem> scoringEvidence = scoringEvidenceFactory.supportedReasonCodes(
                reasonCodes,
                ScoringEvidenceSource.RULE_BASED_SCORING,
                riskLevel,
                inferenceTimestamp
        );

        scoreDetails.put("baseScore", BASE_SCORE);
        scoreDetails.put("finalScore", cappedScore);
        scoreDetails.put("riskLevel", riskLevel.name());
        scoreDetails.put("rulesInputVersion", MODEL_VERSION);
        Map<String, Object> explanationMetadata = Map.of(
                "engineType", "RULE_BASED",
                "explanationType", "CANONICAL_WEIGHTED_REASON_CODES",
                "reasonCodeCount", reasonCodes.size()
        );
        scoreDetails.put("explanationMetadata", explanationMetadata);

        return new FraudScoreResult(
                cappedScore,
                riskLevel,
                "RULE_BASED",
                MODEL_NAME,
                MODEL_VERSION,
                inferenceTimestamp,
                new ArrayList<>(reasonCodes),
                scoreDetails,
                input.featureSnapshot(),
                explanationMetadata,
                alertRecommended,
                scoringEvidence
        );
    }

    private double addIf(
            boolean condition,
            ReasonCode reasonCode,
            double weight,
            double currentScore,
            Set<String> reasonCodes,
            Map<String, Object> scoreDetails
    ) {
        if (!condition) {
            return currentScore;
        }
        reasonCodes.add(reasonCode.wireValue());
        scoreDetails.put(scoreDetailWeightKey(reasonCode), weight);
        return currentScore + weight;
    }

    private boolean highAmountActivity(RulesV2ValidatedInput input) {
        return input.recentTransactionCount() >= 2
                && input.recentAmountSumPln().compareTo(HIGH_AMOUNT_ACTIVITY_THRESHOLD_PLN) >= 0;
    }

    private String scoreDetailWeightKey(ReasonCode reasonCode) {
        if (reasonCode == ReasonCode.DEVICE_NOVELTY) {
            return "deviceNoveltyRulesV2Weight";
        }
        if (reasonCode == ReasonCode.COUNTRY_MISMATCH) {
            return "countryMismatchRulesV2Weight";
        }
        if (reasonCode == ReasonCode.PROXY_OR_VPN) {
            return "proxyOrVpnRulesV2Weight";
        }
        if (reasonCode == ReasonCode.MERCHANT_CONCENTRATION) {
            return "merchantConcentrationRulesV2Weight";
        }
        if (reasonCode == ReasonCode.HIGH_VELOCITY) {
            return "highVelocityRulesV2Weight";
        }
        if (reasonCode == ReasonCode.HIGH_AMOUNT_ACTIVITY) {
            return "highAmountActivityRulesV2Weight";
        }
        if (reasonCode == ReasonCode.RAPID_PLN_20K_BURST) {
            return "rapidPln20kBurstRulesV2Weight";
        }
        return reasonCode.wireValue().toLowerCase() + "RulesV2Weight";
    }

    private double roundScore(double score) {
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private RiskLevel mapRiskLevel(double fraudScore) {
        if (fraudScore >= scoringProperties.criticalThreshold()) {
            return RiskLevel.CRITICAL;
        }
        if (fraudScore >= scoringProperties.highThreshold()) {
            return RiskLevel.HIGH;
        }
        if (fraudScore >= 0.45d) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }
}
