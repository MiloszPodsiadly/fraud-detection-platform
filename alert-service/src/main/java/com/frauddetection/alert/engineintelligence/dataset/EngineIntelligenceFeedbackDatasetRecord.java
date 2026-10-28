package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceReasonCodePolicy;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        Map<String, List<String>> reasonCodesByEngine,
        List<String> diagnosticSignals,
        Integer engineIntelligenceContractVersion,
        FraudEngineStatus deviceEngineStatus,
        EngineIntelligenceScoreBucket deviceScoreBucket,
        RiskLevel deviceRiskLevel
) {

    static final int MAX_REASON_CODE_OCCURRENCES = FraudEngineIdentityContract.maxEngineIntelligenceEngines()
            * EngineIntelligenceProjectionPolicy.MAX_REASON_CODES_PER_ENGINE;
    static final int MAX_DIAGNOSTIC_SIGNALS = EngineIntelligenceProjectionPolicy.MAX_DIAGNOSTIC_SIGNALS;

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
        reasonCodesByEngine = EngineIntelligenceFeedbackDatasetSafety.copyEngineReasonCodesByEngine(reasonCodesByEngine);
        List<String> flattenedReasonCodes = reasonCodesByEngine.values().stream()
                .flatMap(List::stream)
                .toList();
        reasonCodes = EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(
                reasonCodes,
                "reasonCodes",
                MAX_REASON_CODE_OCCURRENCES,
                EngineIntelligenceReasonCodePolicy::isKnownReasonCode);
        if (!reasonCodes.equals(flattenedReasonCodes)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_REASON_CODE_GROUPING_MISMATCH");
        }
        diagnosticSignals = EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(
                diagnosticSignals,
                "diagnosticSignals",
                MAX_DIAGNOSTIC_SIGNALS,
                EngineIntelligenceReasonCodePolicy::isKnownReasonCode
        );
        if (projectionStatus == EngineIntelligenceFeedbackDatasetProjectionStatus.AVAILABLE
                && (!reasonCodesByEngine.containsKey(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID)
                        || !reasonCodesByEngine.containsKey(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID))) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_REQUIRED_ENGINES_MISSING");
        }
        if (projectionStatus == EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING
                && (!reasonCodes.isEmpty() || !reasonCodesByEngine.isEmpty() || !diagnosticSignals.isEmpty())) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_MISSING_PROJECTION_HAS_SIGNALS");
        }
        if (engineIntelligenceContractVersion != null
                && engineIntelligenceContractVersion != FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_CONTRACT_VERSION_UNSUPPORTED");
        }
        if ((deviceEngineStatus == null) != (deviceScoreBucket == null)
                || (deviceEngineStatus != null && deviceRiskLevel != null)
                || (deviceEngineStatus != null && !Objects.equals(
                        engineIntelligenceContractVersion,
                        FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION))
                || (deviceEngineStatus != null && deviceEngineStatus == FraudEngineStatus.AVAILABLE)
                || (deviceEngineStatus != null && deviceScoreBucket != EngineIntelligenceScoreBucket.UNAVAILABLE)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_DEVICE_STATE_INVALID");
        }
    }

}
