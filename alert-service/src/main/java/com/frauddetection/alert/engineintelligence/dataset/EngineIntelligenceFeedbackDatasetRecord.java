package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;

import java.time.Instant;
import java.util.List;

public record EngineIntelligenceFeedbackDatasetRecord(
        String transactionId,
        EngineIntelligenceFeedbackDatasetLabel feedbackLabel,
        AnalystDecision analystDecision,
        EngineIntelligenceFeedbackDatasetLabelSource labelSource,
        EngineIntelligenceFeedbackType feedbackType,
        EngineIntelligenceFeedbackUsefulness usefulness,
        EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment,
        RiskLevel ruleRiskLevel,
        RiskLevel mlRiskLevel,
        EngineIntelligenceScoreBucket ruleScoreBucket,
        EngineIntelligenceScoreBucket mlScoreBucket,
        EngineIntelligenceAgreementStatus engineAgreement,
        EngineIntelligenceRiskMismatchStatus riskMismatch,
        List<String> reasonCodes,
        List<EngineIntelligenceFeedbackDatasetDiagnosticSignal> diagnosticSignals,
        Integer engineIntelligenceContractVersion,
        Instant engineGeneratedAt,
        Instant feedbackCreatedAt,
        Instant decidedAt,
        EngineIntelligenceFeedbackDatasetProjectionStatus engineIntelligenceProjectionStatus
) {
    public EngineIntelligenceFeedbackDatasetRecord {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        diagnosticSignals = diagnosticSignals == null ? List.of() : List.copyOf(diagnosticSignals);
    }
}
