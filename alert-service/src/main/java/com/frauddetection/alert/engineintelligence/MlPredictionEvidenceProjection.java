package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceV1;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

@Document(collection = "ml_prediction_evidence_projections")
public class MlPredictionEvidenceProjection {

    private static final int MAX_IDENTITY_LENGTH = 128;

    @Id
    private final String sourceEventId;
    private final String transactionId;
    private final String correlationId;
    private final String sourceEventCreatedAt;
    private final int contractVersion;
    private final String sourceEngineId;
    private final FraudEngineStatus engineStatus;
    private final Double mlScore;
    private final RiskLevel mlRiskLevel;
    private final String modelName;
    private final String modelVersion;
    private final String featureContractVersion;
    private final String sourceExecutionTimestamp;
    private final Instant projectedAt;

    @PersistenceCreator
    public MlPredictionEvidenceProjection(
            String sourceEventId,
            String transactionId,
            String correlationId,
            String sourceEventCreatedAt,
            int contractVersion,
            String sourceEngineId,
            FraudEngineStatus engineStatus,
            Double mlScore,
            RiskLevel mlRiskLevel,
            String modelName,
            String modelVersion,
            String featureContractVersion,
            String sourceExecutionTimestamp,
            Instant projectedAt
    ) {
        this.sourceEventId = requiredIdentity(sourceEventId, "sourceEventId");
        this.transactionId = requiredIdentity(transactionId, "transactionId");
        this.correlationId = requiredIdentity(correlationId, "correlationId");
        this.sourceEventCreatedAt = validatedInstantText(sourceEventCreatedAt);
        MlPredictionEvidenceV1 validated;
        try {
            validated = new MlPredictionEvidenceV1(
                    contractVersion,
                    sourceEngineId,
                    engineStatus,
                    mlScore,
                    mlRiskLevel,
                    modelName,
                    modelVersion,
                    featureContractVersion,
                    Instant.parse(requiredIdentity(sourceExecutionTimestamp, "sourceExecutionTimestamp"))
            );
        } catch (MlPredictionEvidenceProjectionShapeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MlPredictionEvidenceProjectionShapeException();
        }
        this.contractVersion = validated.contractVersion();
        this.sourceEngineId = validated.sourceEngineId();
        this.engineStatus = validated.engineStatus();
        this.mlScore = validated.mlScore();
        this.mlRiskLevel = validated.mlRiskLevel();
        this.modelName = validated.modelName();
        this.modelVersion = validated.modelVersion();
        this.featureContractVersion = validated.featureContractVersion();
        this.sourceExecutionTimestamp = validated.sourceExecutionTimestamp().toString();
        this.projectedAt = requiredInstant(projectedAt);
    }

    public static MlPredictionEvidenceProjection create(
            String sourceEventId,
            String transactionId,
            String correlationId,
            Instant sourceEventCreatedAt,
            MlPredictionEvidenceV1 evidence,
            Instant projectedAt
    ) {
        MlPredictionEvidenceV1 source = Objects.requireNonNull(evidence, "evidence is required");
        return new MlPredictionEvidenceProjection(
                sourceEventId,
                transactionId,
                correlationId,
                sourceEventCreatedAt.toString(),
                source.contractVersion(),
                source.sourceEngineId(),
                source.engineStatus(),
                source.mlScore(),
                source.mlRiskLevel(),
                source.modelName(),
                source.modelVersion(),
                source.featureContractVersion(),
                source.sourceExecutionTimestamp().toString(),
                projectedAt
        );
    }

    public String getSourceEventId() { return sourceEventId; }
    public String getTransactionId() { return transactionId; }
    public String getCorrelationId() { return correlationId; }
    public Instant getSourceEventCreatedAt() { return Instant.parse(sourceEventCreatedAt); }
    public int getContractVersion() { return contractVersion; }
    public String getSourceEngineId() { return sourceEngineId; }
    public FraudEngineStatus getEngineStatus() { return engineStatus; }
    public Double getMlScore() { return mlScore; }
    public RiskLevel getMlRiskLevel() { return mlRiskLevel; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public String getFeatureContractVersion() { return featureContractVersion; }
    public Instant getSourceExecutionTimestamp() { return Instant.parse(sourceExecutionTimestamp); }
    public Instant getProjectedAt() { return projectedAt; }

    public boolean sameAuthoritativeOccurrence(MlPredictionEvidenceProjection other) {
        return other != null
                && sourceEventId.equals(other.sourceEventId)
                && transactionId.equals(other.transactionId)
                && correlationId.equals(other.correlationId)
                && sourceEventCreatedAt.equals(other.sourceEventCreatedAt)
                && contractVersion == other.contractVersion
                && sourceEngineId.equals(other.sourceEngineId)
                && engineStatus == other.engineStatus
                && mlScore.equals(other.mlScore)
                && mlRiskLevel == other.mlRiskLevel
                && modelName.equals(other.modelName)
                && modelVersion.equals(other.modelVersion)
                && featureContractVersion.equals(other.featureContractVersion)
                && sourceExecutionTimestamp.equals(other.sourceExecutionTimestamp);
    }

    private static String requiredIdentity(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_IDENTITY_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new MlPredictionEvidenceProjectionShapeException();
        }
        return value;
    }

    private static Instant requiredInstant(Instant value) {
        if (value == null) {
            throw new MlPredictionEvidenceProjectionShapeException();
        }
        return value;
    }

    private static String validatedInstantText(String value) {
        try {
            return Instant.parse(requiredIdentity(value, "instant")).toString();
        } catch (MlPredictionEvidenceProjectionShapeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MlPredictionEvidenceProjectionShapeException();
        }
    }
}
