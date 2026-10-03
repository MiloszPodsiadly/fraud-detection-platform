package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceV1;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;

import java.util.Objects;
import java.util.Optional;

final class MlPredictionEvidenceMapper {

    Optional<MlPredictionEvidenceV1> map(
            FraudScoringOrchestrationResult orchestrationResult,
            FraudEngineAggregationResult aggregationResult
    ) {
        Objects.requireNonNull(orchestrationResult, "orchestrationResult is required");
        Objects.requireNonNull(aggregationResult, "aggregationResult is required");

        FraudEngineResult source = orchestrationResult.engineResults().stream()
                .filter(result -> FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(result.engineId()))
                .findFirst()
                .orElse(null);
        if (!usableMlResult(source)) {
            return Optional.empty();
        }

        NormalizedFraudEngineResult normalized = aggregationResult.normalizedEngineResults().stream()
                .filter(result -> FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(result.engineId()))
                .findFirst()
                .orElse(null);
        if (!sameAcceptedPrediction(source, normalized)) {
            return Optional.empty();
        }

        return Optional.of(new MlPredictionEvidenceV1(
                source.score(),
                source.riskLevel(),
                new MlModelIdentity(source.modelName(), source.modelVersion(), source.featureContractVersion()),
                source.generatedAt()
        ));
    }

    private boolean usableMlResult(FraudEngineResult result) {
        return result != null
                && result.engineType() == FraudEngineType.ML_MODEL
                && result.status() == FraudEngineStatus.AVAILABLE;
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
