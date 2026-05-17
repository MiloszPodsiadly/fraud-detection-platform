package com.frauddetection.scoring.service;

import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.events.reason.ReasonCodeParseResult;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;
import com.frauddetection.scoring.observability.ScoringMetrics;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MlFraudScoringEngine implements FraudScoringEngine {

    private final MlModelScoringClient mlModelScoringClient;
    private final ScoringMetrics scoringMetrics;

    public MlFraudScoringEngine(MlModelScoringClient mlModelScoringClient, ScoringMetrics scoringMetrics) {
        this.mlModelScoringClient = mlModelScoringClient;
        this.scoringMetrics = scoringMetrics;
    }

    @Override
    public FraudScoreResult score(FraudScoringRequest request) {
        MlModelOutput output = mlModelScoringClient.score(MlModelInput.from(request));
        List<ReasonCodeParseResult> parsedReasonCodes = ReasonCode.parseLegacyList(output.reasonCodes());
        int unsupportedReasonCodeCount = unsupportedReasonCodeCount(parsedReasonCodes);
        Map<String, Object> scoreDetails = copyOf(output.scoreDetails());
        Map<String, Object> explanationMetadata = copyOf(output.explanationMetadata());
        if (unsupportedReasonCodeCount > 0) {
            scoreDetails.put("unsupportedReasonCodeCount", unsupportedReasonCodeCount);
            explanationMetadata.put("unsupportedReasonCodeCount", unsupportedReasonCodeCount);
            scoringMetrics.recordReasonCodeParseUnsupported("ml_model", "legacy", unsupportedReasonCodeCount);
        }

        return new FraudScoreResult(
                output.fraudScore(),
                output.riskLevel(),
                "ML",
                output.modelName(),
                output.modelVersion(),
                output.inferenceTimestamp(),
                ReasonCode.supportedWireValues(parsedReasonCodes),
                scoreDetails,
                request.featureSnapshot(),
                explanationMetadata,
                output.available() && (output.riskLevel() == com.frauddetection.common.events.enums.RiskLevel.HIGH
                        || output.riskLevel() == com.frauddetection.common.events.enums.RiskLevel.CRITICAL)
        );
    }

    private int unsupportedReasonCodeCount(List<ReasonCodeParseResult> parsedReasonCodes) {
        int count = 0;
        for (ReasonCodeParseResult parsedReasonCode : parsedReasonCodes) {
            if (!parsedReasonCode.supported()) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> copyOf(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
