package com.frauddetection.scoring.service;

import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;
import org.springframework.stereotype.Component;

@Component
public class MlFraudScoringEngine implements FraudScoringEngine {

    private final MlModelScoringClient mlModelScoringClient;

    public MlFraudScoringEngine(MlModelScoringClient mlModelScoringClient) {
        this.mlModelScoringClient = mlModelScoringClient;
    }

    @Override
    public FraudScoreResult score(FraudScoringRequest request) {
        MlModelOutput output = mlModelScoringClient.score(MlModelInput.from(request));

        return new FraudScoreResult(
                output.fraudScore(),
                output.riskLevel(),
                "ML",
                output.modelName(),
                output.modelVersion(),
                output.inferenceTimestamp(),
                output.reasonCodes(),
                output.scoreDetails(),
                request.featureSnapshot(),
                output.explanationMetadata(),
                output.available() && (output.riskLevel() == com.frauddetection.common.events.enums.RiskLevel.HIGH
                        || output.riskLevel() == com.frauddetection.common.events.enums.RiskLevel.CRITICAL)
        );
    }
}
