package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.evidence.ScoringEvidenceFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RuleBasedFraudScoringEngine implements FraudScoringEngine {

    private final ScoringProperties scoringProperties;
    private final ScoringEvidenceFactory scoringEvidenceFactory = new ScoringEvidenceFactory();

    public RuleBasedFraudScoringEngine(ScoringProperties scoringProperties) {
        this.scoringProperties = scoringProperties;
    }

    @Override
    public FraudScoreResult score(FraudScoringRequest request) {
        TransactionEnrichedEvent event = request.event();
        double score = 0.05d;
        Map<String, Object> scoreDetails = new LinkedHashMap<>();
        Set<String> reasonCodes = new LinkedHashSet<>();

        score = addFlagWeight(event.featureFlags(), ReasonCode.DEVICE_NOVELTY, score, 0.18d, reasonCodes, scoreDetails);
        score = addFlagWeight(event.featureFlags(), ReasonCode.COUNTRY_MISMATCH, score, 0.24d, reasonCodes, scoreDetails);
        score = addFlagWeight(event.featureFlags(), ReasonCode.PROXY_OR_VPN, score, 0.16d, reasonCodes, scoreDetails);
        score = addFlagWeight(event.featureFlags(), ReasonCode.MERCHANT_CONCENTRATION, score, 0.08d, reasonCodes, scoreDetails);
        score = RulesScoringPolicyV1.applySemanticVelocityAndAmountRules(event, score, reasonCodes, scoreDetails);

        if (Boolean.TRUE.equals(event.countryMismatch())) {
            score += 0.12d;
            reasonCodes.add(ReasonCode.COUNTRY_MISMATCH.wireValue());
            scoreDetails.put("countryMismatchBoost", 0.12d);
        }
        if (Boolean.TRUE.equals(event.deviceNovelty())) {
            score += 0.10d;
            reasonCodes.add(ReasonCode.DEVICE_NOVELTY.wireValue());
            scoreDetails.put("deviceNoveltyBoost", 0.10d);
        }
        if (Boolean.TRUE.equals(event.proxyOrVpnDetected())) {
            score += 0.10d;
            reasonCodes.add(ReasonCode.PROXY_OR_VPN.wireValue());
            scoreDetails.put("proxyOrVpnBoost", 0.10d);
        }
        if (event.transactionAmount() != null && event.transactionAmount().amount().compareTo(BigDecimal.valueOf(1000)) >= 0) {
            reasonCodes.add(ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue());
            scoreDetails.put("highTransactionAmountDiagnostic", true);
        }
        double cappedScore = Math.min(score, 0.99d);
        RiskLevel riskLevel = mapRiskLevel(cappedScore);
        boolean alertRecommended = riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
        Instant inferenceTimestamp = Instant.now();
        List<ScoringEvidenceItem> scoringEvidence = scoringEvidenceFactory.supportedReasonCodes(
                reasonCodes,
                ScoringEvidenceSource.RULE_BASED_SCORING,
                riskLevel,
                inferenceTimestamp
        );
        if (alertRecommended && scoringEvidence.isEmpty()) {
            scoringEvidence = List.of(scoringEvidenceFactory.missingSupportedReasonCodes(
                    ScoringEvidenceSource.RULE_BASED_SCORING,
                    riskLevel,
                    inferenceTimestamp,
                    0
            ));
        }

        scoreDetails.put("baseScore", 0.05d);
        scoreDetails.put("finalScore", cappedScore);
        scoreDetails.put("riskLevel", riskLevel.name());
        scoreDetails.put("featureFlags", List.copyOf(event.featureFlags()));
        Map<String, Object> explanationMetadata = Map.of(
                "engineType", "RULE_BASED",
                "explanationType", "WEIGHTED_REASON_CODES",
                "reasonCodeCount", reasonCodes.size()
        );
        scoreDetails.put("explanationMetadata", explanationMetadata);

        return new FraudScoreResult(
                cappedScore,
                riskLevel,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                inferenceTimestamp,
                new ArrayList<>(reasonCodes),
                scoreDetails,
                request.featureSnapshot(),
                explanationMetadata,
                alertRecommended,
                scoringEvidence
        );
    }

    private double addFlagWeight(
            List<String> featureFlags,
            ReasonCode reasonCode,
            double currentScore,
            double weight,
            Set<String> reasonCodes,
            Map<String, Object> scoreDetails
    ) {
        String featureFlag = reasonCode.wireValue();
        if (featureFlags != null && featureFlags.contains(featureFlag)) {
            reasonCodes.add(featureFlag);
            scoreDetails.put(featureFlag.toLowerCase() + "Weight", weight);
            return currentScore + weight;
        }
        return currentScore;
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
