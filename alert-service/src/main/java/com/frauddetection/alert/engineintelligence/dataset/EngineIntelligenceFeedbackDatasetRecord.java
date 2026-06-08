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

import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
        List<String> diagnosticSignals
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
        reasonCodes = EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(reasonCodes, "reasonCodes", 10);
        diagnosticSignals = EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(
                diagnosticSignals,
                "diagnosticSignals",
                10
        );
    }
}
