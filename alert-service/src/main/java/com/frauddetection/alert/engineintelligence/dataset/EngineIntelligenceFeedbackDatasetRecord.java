package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceVersionedIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record EngineIntelligenceFeedbackDatasetRecord(
        String evaluationRecordId,
        String transactionReference,
        Instant feedbackSubmittedAt,
        EngineIntelligenceFeedbackDatasetLabel evaluationLabel,
        EngineIntelligenceFeedbackDatasetLabelSource labelSource,
        EngineIntelligenceFeedbackType feedbackType,
        EngineIntelligenceFeedbackUsefulness usefulness,
        EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment,
        EngineIntelligenceFeedbackDatasetProjectionStatus projectionStatus,
        EngineIntelligenceAgreementStatus agreementStatus,
        EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
        EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
        FraudEngineStatus mlEngineStatus,
        EngineIntelligenceScoreBucket mlScoreBucket,
        RiskLevel mlRiskLevel,
        FraudEngineStatus rulesEngineStatus,
        EngineIntelligenceScoreBucket rulesScoreBucket,
        RiskLevel rulesRiskLevel,
        List<String> reasonCodes,
        List<String> diagnosticSignals,
        Integer engineIntelligenceContractVersion,
        FraudEngineStatus deviceEngineStatus,
        EngineIntelligenceScoreBucket deviceScoreBucket,
        RiskLevel deviceRiskLevel
) {

    public EngineIntelligenceFeedbackDatasetRecord {
        evaluationRecordId = EngineIntelligenceFeedbackDatasetSafety.requireEvaluationRecordId(evaluationRecordId);
        transactionReference = EngineIntelligenceFeedbackDatasetSafety.requireTransactionReference(transactionReference);
        feedbackSubmittedAt = Objects.requireNonNull(feedbackSubmittedAt, "feedbackSubmittedAt is required");
        evaluationLabel = Objects.requireNonNull(evaluationLabel, "evaluationLabel is required");
        labelSource = Objects.requireNonNull(labelSource, "labelSource is required");
        feedbackType = Objects.requireNonNull(feedbackType, "feedbackType is required");
        usefulness = Objects.requireNonNull(usefulness, "usefulness is required");
        accuracyAssessment = Objects.requireNonNull(accuracyAssessment, "accuracyAssessment is required");
        projectionStatus = Objects.requireNonNull(projectionStatus, "projectionStatus is required");
        boolean hasBoundedDeviceState = deviceEngineStatus != null
                && Objects.equals(engineIntelligenceContractVersion, EngineIntelligenceVersionedIdentity.V2);
        Set<String> deviceReasonCodes = hasBoundedDeviceState
                ? EngineIntelligenceFeedbackDatasetSafety.DEVICE_ALLOWED_REASON_CODES
                : Set.of();
        reasonCodes = EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(
                reasonCodes, "reasonCodes", 10, deviceReasonCodes);
        diagnosticSignals = EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(
                diagnosticSignals,
                "diagnosticSignals",
                10,
                deviceReasonCodes
        );
        if (engineIntelligenceContractVersion != null
                && !EngineIntelligenceVersionedIdentity.supportedVersion(engineIntelligenceContractVersion)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_CONTRACT_VERSION_UNSUPPORTED");
        }
        if ((deviceEngineStatus == null) != (deviceScoreBucket == null)
                || (deviceEngineStatus != null && deviceRiskLevel != null)
                || (deviceEngineStatus != null && !Objects.equals(
                        engineIntelligenceContractVersion, EngineIntelligenceVersionedIdentity.V2))
                || (deviceEngineStatus != null && deviceEngineStatus == FraudEngineStatus.AVAILABLE)
                || (deviceEngineStatus != null && deviceScoreBucket != EngineIntelligenceScoreBucket.UNAVAILABLE)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_DEVICE_STATE_INVALID");
        }
    }

    public EngineIntelligenceFeedbackDatasetRecord(
            String evaluationRecordId,
            String transactionReference,
            Instant feedbackSubmittedAt,
            EngineIntelligenceFeedbackDatasetLabel evaluationLabel,
            EngineIntelligenceFeedbackDatasetLabelSource labelSource,
            EngineIntelligenceFeedbackType feedbackType,
            EngineIntelligenceFeedbackUsefulness usefulness,
            EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment,
            EngineIntelligenceFeedbackDatasetProjectionStatus projectionStatus,
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            FraudEngineStatus mlEngineStatus,
            EngineIntelligenceScoreBucket mlScoreBucket,
            RiskLevel mlRiskLevel,
            FraudEngineStatus rulesEngineStatus,
            EngineIntelligenceScoreBucket rulesScoreBucket,
            RiskLevel rulesRiskLevel,
            List<String> reasonCodes,
            List<String> diagnosticSignals
    ) {
        this(evaluationRecordId, transactionReference, feedbackSubmittedAt, evaluationLabel, labelSource,
                feedbackType, usefulness, accuracyAssessment, projectionStatus, agreementStatus, riskMismatchStatus,
                scoreDeltaBucket, mlEngineStatus, mlScoreBucket, mlRiskLevel, rulesEngineStatus, rulesScoreBucket,
                rulesRiskLevel, reasonCodes, diagnosticSignals, null, null, null, null);
    }
}
