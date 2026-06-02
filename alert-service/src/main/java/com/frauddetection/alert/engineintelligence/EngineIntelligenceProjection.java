package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "engine_intelligence_projections")
public class EngineIntelligenceProjection {

    @Id
    private final String transactionId;
    private final int contractVersion;
    private final Instant generatedAt;
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

    public EngineIntelligenceProjection(
            String transactionId,
            int contractVersion,
            Instant generatedAt,
            EngineIntelligenceAgreementStatus comparisonStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            List<EngineIntelligenceEngineProjection> engines,
            List<EngineIntelligenceDiagnosticSignalProjection> diagnosticSignals,
            List<EngineIntelligenceWarningProjection> warnings,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.transactionId = transactionId;
        this.contractVersion = contractVersion;
        this.generatedAt = generatedAt;
        this.comparisonStatus = comparisonStatus;
        this.riskMismatchStatus = riskMismatchStatus;
        this.scoreDeltaBucket = scoreDeltaBucket;
        this.engines = List.copyOf(engines);
        this.diagnosticSignals = List.copyOf(diagnosticSignals);
        this.warnings = List.copyOf(warnings);
        this.engineCount = engines.size();
        this.diagnosticSignalCount = diagnosticSignals.size();
        this.warningCount = warnings.size();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getTransactionId() { return transactionId; }
    public int getContractVersion() { return contractVersion; }
    public Instant getGeneratedAt() { return generatedAt; }
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
