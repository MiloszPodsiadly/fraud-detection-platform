package com.frauddetection.scoring.service;

import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
public class RuleBasedFraudScoringEngine implements FraudScoringEngine {

    private final RulesScoringPolicyV2 rulesV2Policy;

    public RuleBasedFraudScoringEngine(ScoringProperties scoringProperties) {
        this.rulesV2Policy = new RulesScoringPolicyV2(scoringProperties);
    }

    @Override
    public FraudScoreResult score(FraudScoringRequest request) {
        Objects.requireNonNull(request, "request is required");
        RulesV2ValidatedInput input = RulesV2InputValidator.requireValidInput(request.event());
        return withFeatureSnapshot(scoreValidated(input), request.featureSnapshot());
    }

    public FraudScoreResult scoreValidated(RulesV2ValidatedInput input) {
        return rulesV2Policy.score(input);
    }

    private FraudScoreResult withFeatureSnapshot(FraudScoreResult result, Map<String, Object> featureSnapshot) {
        return new FraudScoreResult(
                result.fraudScore(),
                result.riskLevel(),
                result.scoringStrategy(),
                result.modelName(),
                result.modelVersion(),
                result.inferenceTimestamp(),
                result.reasonCodes(),
                result.scoreDetails(),
                featureSnapshot,
                result.explanationMetadata(),
                result.alertRecommended(),
                result.scoringEvidence()
        );
    }
}
