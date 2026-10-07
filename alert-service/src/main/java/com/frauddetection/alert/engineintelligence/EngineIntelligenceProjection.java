package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "engine_intelligence_projections")
public class EngineIntelligenceProjection {

    @Id
    private final String transactionId;
    private final String sourceEventId;
    private final String sourceEventCreatedAt;
    private final Long sourceEventCreatedAtEpochSecond;
    private final Integer sourceEventCreatedAtNano;
    private final String sourceEventFingerprint;
    private final int contractVersion;
    private final Instant generatedAt;
    private final EngineIntelligenceComparisonType comparisonType;
    private final List<String> comparedEngineIds;
    private final EngineIntelligenceAgreementStatus comparisonStatus;
    private final EngineIntelligenceRiskMismatchStatus riskMismatchStatus;
    private final EngineIntelligenceScoreDeltaBucket scoreDeltaBucket;
    private final int engineCount;
    private final int diagnosticSignalCount;
    private final int warningCount;
    private final List<EngineIntelligenceEngineProjection> engines;
    private final List<EngineIntelligenceDiagnosticSignalProjection> diagnosticSignals;
    private final List<EngineIntelligenceWarningProjection> warnings;
    private final Instant createdAt;
    private final Instant updatedAt;

    @PersistenceCreator
    private EngineIntelligenceProjection(
            String transactionId,
            String sourceEventId,
            String sourceEventCreatedAt,
            Long sourceEventCreatedAtEpochSecond,
            Integer sourceEventCreatedAtNano,
            String sourceEventFingerprint,
            int contractVersion,
            Instant generatedAt,
            EngineIntelligenceComparisonType comparisonType,
            List<String> comparedEngineIds,
            EngineIntelligenceAgreementStatus comparisonStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            List<EngineIntelligenceEngineProjection> engines,
            List<EngineIntelligenceDiagnosticSignalProjection> diagnosticSignals,
            List<EngineIntelligenceWarningProjection> warnings,
            Instant createdAt,
            Instant updatedAt
    ) {
        ScoringOccurrenceOwnership ownership = ScoringOccurrenceOwnership.fromPersistedIdentity(
                sourceEventId,
                sourceEventCreatedAt,
                sourceEventCreatedAtEpochSecond,
                sourceEventCreatedAtNano,
                sourceEventFingerprint
        );
        this.transactionId = transactionId;
        this.sourceEventId = ownership.sourceEventId();
        this.sourceEventCreatedAt = ownership.sourceEventCreatedAt().toString();
        this.sourceEventCreatedAtEpochSecond = ownership.sourceEventCreatedAt().getEpochSecond();
        this.sourceEventCreatedAtNano = ownership.sourceEventCreatedAt().getNano();
        this.sourceEventFingerprint = ownership.sourceEventFingerprint();
        this.contractVersion = contractVersion;
        this.generatedAt = generatedAt;
        this.comparisonType = comparisonType;
        this.comparedEngineIds = comparedEngineIds == null ? null : List.copyOf(comparedEngineIds);
        this.comparisonStatus = comparisonStatus;
        this.riskMismatchStatus = riskMismatchStatus;
        this.scoreDeltaBucket = scoreDeltaBucket;
        this.engines = engines == null ? List.of() : List.copyOf(engines);
        this.diagnosticSignals = diagnosticSignals == null ? List.of() : List.copyOf(diagnosticSignals);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        this.engineCount = this.engines.size();
        this.diagnosticSignalCount = this.diagnosticSignals.size();
        this.warningCount = this.warnings.size();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public EngineIntelligenceProjection(
            String transactionId,
            ScoringOccurrenceOwnership ownership,
            int contractVersion,
            Instant generatedAt,
            EngineIntelligenceComparisonType comparisonType,
            List<String> comparedEngineIds,
            EngineIntelligenceAgreementStatus comparisonStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            List<EngineIntelligenceEngineProjection> engines,
            List<EngineIntelligenceDiagnosticSignalProjection> diagnosticSignals,
            List<EngineIntelligenceWarningProjection> warnings,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                transactionId,
                requiredOwnership(ownership).sourceEventId(),
                requiredOwnership(ownership).sourceEventCreatedAt().toString(),
                requiredOwnership(ownership).sourceEventCreatedAt().getEpochSecond(),
                requiredOwnership(ownership).sourceEventCreatedAt().getNano(),
                requiredOwnership(ownership).sourceEventFingerprint(),
                contractVersion,
                generatedAt,
                comparisonType,
                comparedEngineIds,
                comparisonStatus,
                riskMismatchStatus,
                scoreDeltaBucket,
                engines,
                diagnosticSignals,
                warnings,
                createdAt,
                updatedAt
        );
    }

    private static ScoringOccurrenceOwnership requiredOwnership(ScoringOccurrenceOwnership ownership) {
        if (ownership == null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_PROJECTION_OCCURRENCE_IDENTITY_REQUIRED");
        }
        return ownership;
    }

    public String getTransactionId() { return transactionId; }
    public String getSourceEventId() { return sourceEventId; }
    public Instant getSourceEventCreatedAt() {
        return scoringOccurrenceOwnership().sourceEventCreatedAt();
    }
    public String getSourceEventCreatedAtText() { return sourceEventCreatedAt; }
    public Long getSourceEventCreatedAtEpochSecond() { return sourceEventCreatedAtEpochSecond; }
    public Integer getSourceEventCreatedAtNano() { return sourceEventCreatedAtNano; }
    public String getSourceEventFingerprint() { return sourceEventFingerprint; }
    public ScoringOccurrenceOwnership scoringOccurrenceOwnership() {
        return ScoringOccurrenceOwnership.fromPersistedIdentity(
                sourceEventId,
                sourceEventCreatedAt,
                sourceEventCreatedAtEpochSecond,
                sourceEventCreatedAtNano,
                sourceEventFingerprint
        );
    }
    public int getContractVersion() { return contractVersion; }
    public Instant getGeneratedAt() { return generatedAt; }
    public EngineIntelligenceComparisonType getComparisonType() { return comparisonType; }
    public List<String> getComparedEngineIds() { return comparedEngineIds; }
    public EngineIntelligenceAgreementStatus getComparisonStatus() { return comparisonStatus; }
    public EngineIntelligenceRiskMismatchStatus getRiskMismatchStatus() { return riskMismatchStatus; }
    public EngineIntelligenceScoreDeltaBucket getScoreDeltaBucket() { return scoreDeltaBucket; }
    public int getEngineCount() { return engineCount; }
    public int getDiagnosticSignalCount() { return diagnosticSignalCount; }
    public int getWarningCount() { return warningCount; }
    public List<EngineIntelligenceEngineProjection> getEngines() { return engines; }
    public List<EngineIntelligenceDiagnosticSignalProjection> getDiagnosticSignals() { return diagnosticSignals; }
    public List<EngineIntelligenceWarningProjection> getWarnings() { return warnings; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

}
