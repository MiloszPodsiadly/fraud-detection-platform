package com.frauddetection.scoring.engine;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.ml.MlModelIdentityPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FraudSignalEvaluation(
        FraudEngineStatus status,
        Double score,
        RiskLevel riskLevel,
        FraudEngineConfidence confidence,
        List<String> reasonCodes,
        List<FraudEngineContribution> contributions,
        List<FraudEngineEvidence> evidence,
        String modelName,
        String modelVersion,
        String featureContractVersion,
        String statusReason,
        Instant sourceInferenceTimestamp,
        String modelArtifactSha256
) {
    public FraudSignalEvaluation {
        Objects.requireNonNull(status, "status is required");
        confidence = confidence == null ? FraudEngineConfidence.UNKNOWN : confidence;
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
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
        boolean carriesMlSpecificIdentity = featureContractVersion != null
                || modelArtifactSha256 != null
                || sourceInferenceTimestamp != null;
        if (carriesMlSpecificIdentity) {
            MlModelIdentityPolicy.requireAtomicArtifactIdentity(
                    modelName,
                    modelVersion,
                    featureContractVersion,
                    modelArtifactSha256
            );
        }
        if (status == FraudEngineStatus.AVAILABLE
                && MlModelIdentityPolicy.hasCompleteArtifactIdentity(
                modelName,
                modelVersion,
                featureContractVersion,
                modelArtifactSha256
        ) && sourceInferenceTimestamp == null) {
            throw new IllegalArgumentException(
                    "AVAILABLE ML model artifact identity requires sourceInferenceTimestamp"
            );
        }
    }

    public FraudSignalEvaluation(
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            List<String> reasonCodes,
            List<FraudEngineContribution> contributions,
            List<FraudEngineEvidence> evidence,
            String modelName,
            String modelVersion,
            String featureContractVersion,
            String statusReason
    ) {
        this(
                status,
                score,
                riskLevel,
                confidence,
                reasonCodes,
                contributions,
                evidence,
                modelName,
                modelVersion,
                featureContractVersion,
                statusReason,
                null,
                null
        );
    }

    public FraudSignalEvaluation(
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            List<String> reasonCodes,
            List<FraudEngineContribution> contributions,
            List<FraudEngineEvidence> evidence,
            String modelName,
            String modelVersion,
            String statusReason
    ) {
        this(
                status,
                score,
                riskLevel,
                confidence,
                reasonCodes,
                contributions,
                evidence,
                modelName,
                modelVersion,
                null,
                statusReason,
                null,
                null
        );
    }

    public FraudSignalEvaluation(
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            List<String> reasonCodes,
            List<FraudEngineContribution> contributions,
            List<FraudEngineEvidence> evidence,
            String modelName,
            String modelVersion,
            String featureContractVersion,
            String statusReason,
            Instant sourceInferenceTimestamp
    ) {
        this(
                status,
                score,
                riskLevel,
                confidence,
                reasonCodes,
                contributions,
                evidence,
                modelName,
                modelVersion,
                featureContractVersion,
                statusReason,
                sourceInferenceTimestamp,
                null
        );
    }
}
