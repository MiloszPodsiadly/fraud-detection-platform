package com.frauddetection.scoring.domain;

import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.ml.MlModelIdentityPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FraudScoreResult(
        Double fraudScore,
        RiskLevel riskLevel,
        String scoringStrategy,
        String modelName,
        String modelVersion,
        String featureContractVersion,
        Instant inferenceTimestamp,
        List<String> reasonCodes,
        Map<String, Object> scoreDetails,
        Map<String, Object> featureSnapshot,
        Map<String, Object> explanationMetadata,
        Boolean alertRecommended,
        List<ScoringEvidenceItem> scoringEvidence
) {
    public FraudScoreResult {
        if ("ML".equals(scoringStrategy)) {
            modelName = MlModelIdentityPolicy.optionalModelName(modelName, "modelName");
            modelVersion = MlModelIdentityPolicy.optionalModelVersion(modelVersion, "modelVersion");
            featureContractVersion = MlModelIdentityPolicy.optionalFeatureContractVersion(
                    featureContractVersion,
                    "featureContractVersion"
            );
            validateAtomicMlModelIdentity(modelName, modelVersion, featureContractVersion);
        }
        scoringEvidence = scoringEvidence == null ? List.of() : List.copyOf(scoringEvidence);
    }

    public FraudScoreResult(
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            String featureContractVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Map<String, Object> explanationMetadata,
            Boolean alertRecommended
    ) {
        this(
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                featureContractVersion,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                explanationMetadata,
                alertRecommended,
                List.of()
        );
    }

    public FraudScoreResult(
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Map<String, Object> explanationMetadata,
            Boolean alertRecommended,
            List<ScoringEvidenceItem> scoringEvidence
    ) {
        this(
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                null,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                explanationMetadata,
                alertRecommended,
                scoringEvidence
        );
    }

    public FraudScoreResult(
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Map<String, Object> explanationMetadata,
            Boolean alertRecommended
    ) {
        this(
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                null,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                explanationMetadata,
                alertRecommended,
                List.of()
        );
    }

    private static void validateAtomicMlModelIdentity(
            String modelName,
            String modelVersion,
            String featureContractVersion
    ) {
        int present = 0;
        present += modelName == null ? 0 : 1;
        present += modelVersion == null ? 0 : 1;
        present += featureContractVersion == null ? 0 : 1;
        if (present != 0 && present != 3) {
            throw new IllegalArgumentException("ML model identity must be entirely absent or complete");
        }
    }
}
