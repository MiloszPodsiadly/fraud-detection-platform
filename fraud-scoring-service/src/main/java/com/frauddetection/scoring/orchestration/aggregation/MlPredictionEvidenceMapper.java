package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;
import com.frauddetection.scoring.engine.ml.PythonMlSignalReasonCode;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;

import java.util.Objects;

final class MlPredictionEvidenceMapper {

    EngineIntelligenceEnrichmentResult map(
            EngineIntelligenceSummary summary,
            FraudScoringOrchestrationResult orchestrationResult,
            FraudEngineAggregationResult aggregationResult
    ) {
        Objects.requireNonNull(summary, "summary is required");
        Objects.requireNonNull(orchestrationResult, "orchestrationResult is required");
        Objects.requireNonNull(aggregationResult, "aggregationResult is required");

        FraudEngineResult source = orchestrationResult.engineResults().stream()
                .filter(result -> FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(result.engineId()))
                .findFirst()
                .orElse(null);
        MlPredictionEvidenceOmissionReason omissionReason = omissionReason(source);
        if (omissionReason != null) {
            return EngineIntelligenceEnrichmentResult.withoutEvidence(summary, omissionReason);
        }

        NormalizedFraudEngineResult normalized = aggregationResult.normalizedEngineResults().stream()
                .filter(result -> FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(result.engineId()))
                .findFirst()
                .orElse(null);
        if (!sameAcceptedPrediction(source, normalized)) {
            return EngineIntelligenceEnrichmentResult.withoutEvidence(
                    summary,
                    MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED
            );
        }

        return EngineIntelligenceEnrichmentResult.withEvidence(summary, new MlPredictionEvidence(
                source.score(),
                source.riskLevel(),
                new MlModelIdentity(source.modelName(), source.modelVersion(), source.featureContractVersion()),
                source.modelArtifactSha256(),
                source.sourceInferenceTimestamp()
        ));
    }

    private MlPredictionEvidenceOmissionReason omissionReason(FraudEngineResult result) {
        if (result == null || result.engineType() != FraudEngineType.ML_MODEL) {
            return MlPredictionEvidenceOmissionReason.EVIDENCE_SOURCE_INTEGRITY_FAILURE;
        }
        if (PythonMlSignalReasonCode.ML_INFERENCE_TIMESTAMP_MISSING.wireValue().equals(result.statusReason())) {
            return MlPredictionEvidenceOmissionReason.SOURCE_TIMESTAMP_MISSING;
        }
        if (PythonMlSignalReasonCode.ML_SCORE_MISSING.wireValue().equals(result.statusReason())
                || PythonMlSignalReasonCode.ML_SCORE_OUT_OF_RANGE.wireValue().equals(result.statusReason())) {
            return MlPredictionEvidenceOmissionReason.INVALID_SCORE;
        }
        if (PythonMlSignalReasonCode.ML_MODEL_METADATA_MISSING.wireValue().equals(result.statusReason())) {
            return MlPredictionEvidenceOmissionReason.IDENTITY_VALIDATION_FAILURE;
        }
        if (result.status() == FraudEngineStatus.UNAVAILABLE
                || result.status() == FraudEngineStatus.TIMEOUT
                || result.status() == FraudEngineStatus.SKIPPED) {
            return MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE;
        }
        if (result.status() != FraudEngineStatus.AVAILABLE) {
            return MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED;
        }
        if (result.sourceInferenceTimestamp() == null) {
            return MlPredictionEvidenceOmissionReason.SOURCE_TIMESTAMP_MISSING;
        }
        return null;
    }

    private boolean sameAcceptedPrediction(
            FraudEngineResult source,
            NormalizedFraudEngineResult normalized
    ) {
        if (normalized == null
                || normalized.engineType() != FraudEngineType.ML_MODEL
                || normalized.status() != FraudEngineStatus.AVAILABLE) {
            return false;
        }
        return Objects.equals(source.score(), normalized.score())
                && source.riskLevel() == normalized.riskLevel()
                && Objects.equals(source.modelName(), normalized.modelIdentity().modelName())
                && Objects.equals(source.modelVersion(), normalized.modelIdentity().modelVersion())
                && Objects.equals(source.featureContractVersion(), normalized.modelIdentity().featureContractVersion());
    }
}
