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
        List<ScoringEvidenceItem> scoringEvidence,
        String modelArtifactSha256
) {
    public FraudScoreResult {
        if ("ML".equals(scoringStrategy)) {
            modelName = MlModelIdentityPolicy.optionalModelName(modelName, "modelName");
            modelVersion = MlModelIdentityPolicy.optionalModelVersion(modelVersion, "modelVersion");
            featureContractVersion = MlModelIdentityPolicy.optionalFeatureContractVersion(
                    featureContractVersion,
                    "featureContractVersion"
            );
            modelArtifactSha256 = MlModelIdentityPolicy.optionalArtifactSha256(
                    modelArtifactSha256,
                    "modelArtifactSha256"
            );
            MlModelIdentityPolicy.requireAtomicArtifactIdentity(
                    modelName,
                    modelVersion,
                    featureContractVersion,
                    modelArtifactSha256
            );
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
                List.of(),
                null
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
                scoringEvidence,
                null
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
                List.of(),
                null
        );
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
            Boolean alertRecommended,
            List<ScoringEvidenceItem> scoringEvidence
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
                scoringEvidence,
                null
        );
    }

}
