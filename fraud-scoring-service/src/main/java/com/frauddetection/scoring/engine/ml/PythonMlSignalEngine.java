package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.service.MlFraudScoringEngine;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public final class PythonMlSignalEngine implements FraudSignalEngine {

    private static final String ENGINE_ID = "ml.python.primary";
    private static final String ENGINE_LANGUAGE = "python";
    private static final String ENGINE_VERSION = "1.0.0";
    private static final String EVIDENCE_SOURCE = "ML_MODEL";

    private final MlFraudScoringEngine mlSource;

    public PythonMlSignalEngine(MlFraudScoringEngine mlSource) {
        this.mlSource = Objects.requireNonNull(mlSource, "mlSource is required");
    }

    @Override
    public FraudEngineResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        try {
            FraudScoreResult sourceResult = mlSource.score(FraudScoringRequest.from(context.transaction()));
            return mapSourceResult(sourceResult, context.receivedAt());
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                return unavailableResult(FraudEngineStatus.TIMEOUT, PythonMlSignalReasonCode.ML_MODEL_TIMEOUT, context.receivedAt());
            }
            return unavailableResult(FraudEngineStatus.UNAVAILABLE, PythonMlSignalReasonCode.ML_CLIENT_ERROR, context.receivedAt());
        }
    }

    @Override
    public FraudEngineDescriptor descriptor() {
        return new FraudEngineDescriptor(ENGINE_ID, FraudEngineType.ML_MODEL, ENGINE_LANGUAGE, ENGINE_VERSION, false);
    }

    private FraudEngineResult mapSourceResult(FraudScoreResult sourceResult, Instant generatedAt) {
        if (sourceResult == null) {
            return degradedResult(PythonMlSignalReasonCode.ML_MODEL_INVALID_RESPONSE, generatedAt);
        }
        if (Boolean.FALSE.equals(modelAvailable(sourceResult))) {
            return unavailableResult(FraudEngineStatus.UNAVAILABLE, PythonMlSignalReasonCode.ML_MODEL_UNAVAILABLE, generatedAt);
        }
        if (sourceResult.fraudScore() == null) {
            return degradedResult(PythonMlSignalReasonCode.ML_SCORE_MISSING, generatedAt);
        }
        if (!Double.isFinite(sourceResult.fraudScore()) || sourceResult.fraudScore() < 0.0d || sourceResult.fraudScore() > 1.0d) {
            return degradedResult(PythonMlSignalReasonCode.ML_SCORE_OUT_OF_RANGE, generatedAt);
        }
        if (sourceResult.riskLevel() == null) {
            return degradedResult(PythonMlSignalReasonCode.ML_MODEL_INVALID_RESPONSE, generatedAt);
        }
        if (missingModelMetadata(sourceResult)) {
            return degradedResult(PythonMlSignalReasonCode.ML_MODEL_METADATA_MISSING, generatedAt);
        }
        return availableResult(sourceResult, generatedAt);
    }

    private Boolean modelAvailable(FraudScoreResult sourceResult) {
        if (sourceResult.explanationMetadata() == null) {
            return null;
        }
        Object value = sourceResult.explanationMetadata().get("modelAvailable");
        return value instanceof Boolean available ? available : null;
    }

    private boolean missingModelMetadata(FraudScoreResult sourceResult) {
        return sourceResult.modelName() == null
                || sourceResult.modelName().isBlank()
                || sourceResult.modelVersion() == null
                || sourceResult.modelVersion().isBlank();
    }

    private FraudEngineResult availableResult(FraudScoreResult sourceResult, Instant generatedAt) {
        PythonMlSignalReasonCode reasonCode = PythonMlSignalReasonCode.ML_MODEL_SIGNAL;
        return new FraudEngineResult(
                ENGINE_ID,
                FraudEngineType.ML_MODEL,
                ENGINE_LANGUAGE,
                FraudEngineStatus.AVAILABLE,
                sourceResult.fraudScore(),
                sourceResult.riskLevel(),
                FraudEngineConfidence.MEDIUM,
                List.of(reasonCode.wireValue()),
                contributions(reasonCode),
                evidence(reasonCode, FraudEngineEvidenceStatus.AVAILABLE),
                0L,
                sourceResult.modelName(),
                sourceResult.modelVersion(),
                null,
                generatedAt
        );
    }

    private FraudEngineResult unavailableResult(
            FraudEngineStatus status,
            PythonMlSignalReasonCode reasonCode,
            Instant generatedAt
    ) {
        return new FraudEngineResult(
                ENGINE_ID,
                FraudEngineType.ML_MODEL,
                ENGINE_LANGUAGE,
                status,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of(reasonCode.wireValue()),
                List.of(),
                evidence(reasonCode, FraudEngineEvidenceStatus.UNAVAILABLE),
                0L,
                null,
                null,
                reasonCode.wireValue(),
                generatedAt
        );
    }

    private FraudEngineResult degradedResult(PythonMlSignalReasonCode reasonCode, Instant generatedAt) {
        return new FraudEngineResult(
                ENGINE_ID,
                FraudEngineType.ML_MODEL,
                ENGINE_LANGUAGE,
                FraudEngineStatus.DEGRADED,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of(reasonCode.wireValue()),
                List.of(),
                evidence(reasonCode, FraudEngineEvidenceStatus.PARTIAL),
                0L,
                null,
                null,
                reasonCode.wireValue(),
                generatedAt
        );
    }

    private List<FraudEngineContribution> contributions(PythonMlSignalReasonCode reasonCode) {
        return List.of(new FraudEngineContribution(
                reasonCode.wireValue(),
                null,
                null,
                FraudEngineContributionDirection.UNKNOWN
        ));
    }

    private List<FraudEngineEvidence> evidence(
            PythonMlSignalReasonCode reasonCode,
            FraudEngineEvidenceStatus status
    ) {
        return List.of(new FraudEngineEvidence(
                evidenceType(status),
                reasonCode.wireValue(),
                title(status),
                "Bounded ML adapter signal.",
                EVIDENCE_SOURCE,
                status
        ));
    }

    private FraudEngineEvidenceType evidenceType(FraudEngineEvidenceStatus status) {
        return status == FraudEngineEvidenceStatus.AVAILABLE
                ? FraudEngineEvidenceType.MODEL_EXPLANATION
                : FraudEngineEvidenceType.OPERATIONAL_FALLBACK;
    }

    private String title(FraudEngineEvidenceStatus status) {
        return status == FraudEngineEvidenceStatus.AVAILABLE ? "ML signal" : "ML status";
    }

    private boolean isTimeout(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
            if (current.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
        }
        return false;
    }
}
