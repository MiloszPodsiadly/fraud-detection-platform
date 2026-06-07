package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.engine.FraudEngineType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class EngineIntelligenceFeedbackDatasetRecordMapper {

    private static final int SUPPORTED_PROJECTION_CONTRACT_VERSION = EngineIntelligenceSummary.CONTRACT_VERSION;

    EngineIntelligenceFeedbackDatasetRecord map(
            EngineIntelligenceFeedbackDocument feedback,
            AlertDocument alert,
            EngineIntelligenceProjection projection
    ) {
        validateFeedback(feedback);
        validateAlert(feedback, alert);
        EngineIntelligenceFeedbackDatasetLabelMapper.MappedLabel label =
                EngineIntelligenceFeedbackDatasetLabelMapper.map(alert.getAnalystDecision());
        EngineSignals signals = projection == null ? EngineSignals.missing() : signalsFrom(feedback, projection);
        return new EngineIntelligenceFeedbackDatasetRecord(
                EngineIntelligenceFeedbackDatasetSafety.evaluationRecordId(feedback.getFeedbackId()),
                EngineIntelligenceFeedbackDatasetSafety.transactionReference(feedback.getTransactionId()),
                feedback.getSubmittedAt(),
                label.evaluationLabel(),
                label.labelSource(),
                feedback.getFeedbackType(),
                feedback.getUsefulness(),
                feedback.getAccuracyAssessment(),
                signals.projectionStatus(),
                signals.agreementStatus(),
                signals.riskMismatchStatus(),
                signals.scoreDeltaBucket(),
                signals.mlEngineStatus(),
                signals.mlScoreBucket(),
                signals.mlRiskLevel(),
                signals.rulesEngineStatus(),
                signals.rulesScoreBucket(),
                signals.rulesRiskLevel(),
                signals.reasonCodes(),
                signals.diagnosticSignals()
        );
    }

    private void validateFeedback(EngineIntelligenceFeedbackDocument feedback) {
        Objects.requireNonNull(feedback, "feedback is required");
        if (feedback.getFeedbackId() == null
                || feedback.getTransactionId() == null
                || feedback.getSubmittedAt() == null
                || feedback.getFeedbackType() == null
                || feedback.getUsefulness() == null
                || feedback.getAccuracyAssessment() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK
            );
        }
        try {
            EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(
                    feedback.getSelectedReasonCodes(),
                    "selectedReasonCodes",
                    5
            );
        } catch (IllegalArgumentException exception) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK
            );
        }
    }

    private void validateAlert(EngineIntelligenceFeedbackDocument feedback, AlertDocument alert) {
        Objects.requireNonNull(alert, "alert is required");
        if (alert.getTransactionId() == null || !feedback.getTransactionId().equals(alert.getTransactionId())) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_ALERT_DATA
            );
        }
    }

    private EngineSignals signalsFrom(EngineIntelligenceFeedbackDocument feedback, EngineIntelligenceProjection projection) {
        if (projection.getTransactionId() == null
                || !feedback.getTransactionId().equals(projection.getTransactionId())
                || projection.getContractVersion() != SUPPORTED_PROJECTION_CONTRACT_VERSION
                || projection.getGeneratedAt() == null
                || projection.getEngines() == null
                || projection.getDiagnosticSignals() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        EngineValues ml = EngineValues.missing();
        EngineValues rules = EngineValues.missing();
        List<String> reasonCodes = new ArrayList<>();
        for (EngineIntelligenceEngineProjection engine : projection.getEngines()) {
            validateEngine(engine);
            reasonCodes.addAll(engine.reasonCodes());
            if (engine.engineType() == FraudEngineType.ML_MODEL) {
                ml = new EngineValues(engine.status(), engine.scoreBucket(), engine.riskLevel());
            }
            if (engine.engineType() == FraudEngineType.RULES) {
                rules = new EngineValues(engine.status(), engine.scoreBucket(), engine.riskLevel());
            }
        }
        List<String> diagnosticSignals = new ArrayList<>();
        for (EngineIntelligenceDiagnosticSignalProjection signal : projection.getDiagnosticSignals()) {
            validateSignal(signal);
            diagnosticSignals.add(signal.reasonCode());
        }
        return new EngineSignals(
                EngineIntelligenceFeedbackDatasetProjectionStatus.AVAILABLE,
                projection.getComparisonStatus(),
                projection.getRiskMismatchStatus(),
                projection.getScoreDeltaBucket(),
                ml.status(),
                ml.scoreBucket(),
                ml.riskLevel(),
                rules.status(),
                rules.scoreBucket(),
                rules.riskLevel(),
                reasonCodes,
                diagnosticSignals
        );
    }

    private void validateEngine(EngineIntelligenceEngineProjection engine) {
        if (engine == null
                || engine.engineId() == null
                || engine.engineType() == null
                || engine.status() == null
                || engine.scoreBucket() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        try {
            EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(engine.reasonCodes(), "reasonCodes", 5);
        } catch (IllegalArgumentException exception) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
    }

    private void validateSignal(EngineIntelligenceDiagnosticSignalProjection signal) {
        if (signal == null || signal.reasonCode() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        try {
            EngineIntelligenceFeedbackDatasetSafety.requireMachineCode(signal.reasonCode(), "diagnosticSignals");
        } catch (IllegalArgumentException exception) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
    }

    private record EngineValues(
            com.frauddetection.common.events.engine.FraudEngineStatus status,
            com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket scoreBucket,
            com.frauddetection.common.events.enums.RiskLevel riskLevel
    ) {
        static EngineValues missing() {
            return new EngineValues(null, null, null);
        }
    }

    private record EngineSignals(
            EngineIntelligenceFeedbackDatasetProjectionStatus projectionStatus,
            com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus agreementStatus,
            com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            com.frauddetection.common.events.engine.FraudEngineStatus mlEngineStatus,
            com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket mlScoreBucket,
            com.frauddetection.common.events.enums.RiskLevel mlRiskLevel,
            com.frauddetection.common.events.engine.FraudEngineStatus rulesEngineStatus,
            com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket rulesScoreBucket,
            com.frauddetection.common.events.enums.RiskLevel rulesRiskLevel,
            List<String> reasonCodes,
            List<String> diagnosticSignals
    ) {
        static EngineSignals missing() {
            return new EngineSignals(
                    EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of()
            );
        }
    }

    static class CorruptedDatasetSourceException extends RuntimeException {

        private final EngineIntelligenceFeedbackDatasetExportFailureReason reason;

        CorruptedDatasetSourceException(EngineIntelligenceFeedbackDatasetExportFailureReason reason) {
            super(reason.name());
            this.reason = reason;
        }

        EngineIntelligenceFeedbackDatasetExportFailureReason reason() {
            return reason;
        }
    }
}
