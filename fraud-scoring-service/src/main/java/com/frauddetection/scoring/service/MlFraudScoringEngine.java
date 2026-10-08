package com.frauddetection.scoring.service;

import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.events.reason.ReasonCodeParseResult;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;
import com.frauddetection.scoring.evidence.ScoringEvidenceFactory;
import com.frauddetection.scoring.observability.ScoringMetrics;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MlFraudScoringEngine implements FraudScoringEngine {

    public static final String MODEL_IDENTITY_VALIDATION_FAILED = "modelIdentityValidationFailed";

    private final MlModelScoringClient mlModelScoringClient;
    private final ScoringMetrics scoringMetrics;
    private final ScoringEvidenceFactory scoringEvidenceFactory = new ScoringEvidenceFactory();

    public MlFraudScoringEngine(MlModelScoringClient mlModelScoringClient, ScoringMetrics scoringMetrics) {
        this.mlModelScoringClient = mlModelScoringClient;
        this.scoringMetrics = scoringMetrics;
    }

    @Override
    public FraudScoreResult score(FraudScoringRequest request) {
        MlModelOutput output = mlModelScoringClient.score(MlModelInput.from(request));
        if (output.available() && missingModelIdentity(output)) {
            return unavailableIdentityResult(request, output);
        }
        List<ReasonCodeParseResult> parsedReasonCodes = ReasonCode.parseInputList(output.reasonCodes());
        int unsupportedReasonCodeCount = unsupportedReasonCodeCount(parsedReasonCodes);
        Map<String, Object> scoreDetails = copyOf(output.scoreDetails());
        Map<String, Object> explanationMetadata = copyOf(output.explanationMetadata());
        explanationMetadata.put("modelAvailable", output.available());
        if (unsupportedReasonCodeCount > 0) {
            scoreDetails.put("unsupportedReasonCodeCount", unsupportedReasonCodeCount);
            explanationMetadata.put("unsupportedReasonCodeCount", unsupportedReasonCodeCount);
            scoringMetrics.recordReasonCodeParseUnsupported("ml_model", "canonical", unsupportedReasonCodeCount);
        }
        List<ScoringEvidenceItem> scoringEvidence = scoringEvidenceFactory.modelEvidence(
                parsedReasonCodes,
                output.available(),
                output.riskLevel(),
                output.inferenceTimestamp(),
                output.fallbackReason()
        );

        return new FraudScoreResult(
                output.fraudScore(),
                output.riskLevel(),
                "ML",
                output.modelName(),
                output.modelVersion(),
                output.featureContractVersion(),
                output.inferenceTimestamp(),
                ReasonCode.supportedWireValues(parsedReasonCodes),
                scoreDetails,
                request.featureSnapshot(),
                explanationMetadata,
                output.available() && (output.riskLevel() == com.frauddetection.common.events.enums.RiskLevel.HIGH
                        || output.riskLevel() == com.frauddetection.common.events.enums.RiskLevel.CRITICAL),
                scoringEvidence,
                output.modelArtifactSha256()
        );
    }

    private boolean missingModelIdentity(MlModelOutput output) {
        return isBlank(output.modelName())
                || isBlank(output.modelVersion())
                || isBlank(output.featureContractVersion())
                || isBlank(output.modelArtifactSha256());
    }

    private FraudScoreResult unavailableIdentityResult(FraudScoringRequest request, MlModelOutput output) {
        String fallbackReason = "ML_MODEL_IDENTITY_MISSING";
        Map<String, Object> scoreDetails = copyOf(output.scoreDetails());
        scoreDetails.put("modelAvailable", false);
        scoreDetails.put("fallbackReason", fallbackReason);
        Map<String, Object> explanationMetadata = copyOf(output.explanationMetadata());
        explanationMetadata.put("modelAvailable", false);
        explanationMetadata.put(MODEL_IDENTITY_VALIDATION_FAILED, true);
        explanationMetadata.put("fallbackReason", fallbackReason);
        List<ReasonCodeParseResult> parsedReasonCodes = ReasonCode.parseInputList(
                List.of(ReasonCode.ML_MODEL_UNAVAILABLE.wireValue())
        );

        return new FraudScoreResult(
                null,
                null,
                "ML",
                null,
                null,
                null,
                output.inferenceTimestamp(),
                ReasonCode.supportedWireValues(parsedReasonCodes),
                scoreDetails,
                request.featureSnapshot(),
                explanationMetadata,
                false,
                scoringEvidenceFactory.modelEvidence(
                        parsedReasonCodes,
                        false,
                        null,
                        output.inferenceTimestamp(),
                        fallbackReason
                ),
                null
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
