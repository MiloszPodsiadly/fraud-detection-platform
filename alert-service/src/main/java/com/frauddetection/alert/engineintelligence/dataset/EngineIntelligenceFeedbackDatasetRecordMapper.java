package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.common.events.intelligence.EngineIntelligenceVersionedIdentity;
import com.frauddetection.common.events.engine.FraudEngineType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class EngineIntelligenceFeedbackDatasetRecordMapper {

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
                signals.diagnosticSignals(),
                signals.engineIntelligenceContractVersion(),
                signals.deviceEngineStatus(),
                signals.deviceScoreBucket(),
                signals.deviceRiskLevel()
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
                || !EngineIntelligenceVersionedIdentity.supportedVersion(projection.getContractVersion())
                || projection.getGeneratedAt() == null
                || projection.getEngines() == null
                || projection.getDiagnosticSignals() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        EngineValues ml = EngineValues.missing();
        EngineValues rules = EngineValues.missing();
        EngineValues device = EngineValues.missing();
        List<String> reasonCodes = new ArrayList<>();
        for (EngineIntelligenceEngineProjection engine : projection.getEngines()) {
            validateEngine(engine, projection.getContractVersion());
            reasonCodes.addAll(engine.reasonCodes());
            if (engine.engineType() == FraudEngineType.ML_MODEL) {
                ml = new EngineValues(engine.status(), engine.scoreBucket(), engine.riskLevel());
            }
            if (engine.engineType() == FraudEngineType.RULES) {
                rules = new EngineValues(engine.status(), engine.scoreBucket(), engine.riskLevel());
            }
            if (EngineIntelligenceVersionedIdentity.DEVICE_PRIMARY_ENGINE_ID.equals(engine.engineId())) {
                device = new EngineValues(engine.status(), engine.scoreBucket(), engine.riskLevel());
            }
        }
        List<String> diagnosticSignals = new ArrayList<>();
        for (EngineIntelligenceDiagnosticSignalProjection signal : projection.getDiagnosticSignals()) {
            validateSignal(signal, projection.getContractVersion());
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
                diagnosticSignals,
                projection.getContractVersion(),
                device.status(),
                device.scoreBucket(),
                device.riskLevel()
        );
    }

    private void validateEngine(EngineIntelligenceEngineProjection engine, int contractVersion) {
        if (engine == null
                || engine.engineId() == null
                || engine.engineType() == null
                || engine.status() == null
                || engine.scoreBucket() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        boolean device = EngineIntelligenceVersionedIdentity.DEVICE_PRIMARY_ENGINE_ID.equals(engine.engineId());
        if (!EngineIntelligenceVersionedIdentity.engineOrder(contractVersion).contains(engine.engineId())
                || !EngineIntelligenceVersionedIdentity.hasExpectedType(engine.engineId(), engine.engineType())
                || device && (contractVersion != EngineIntelligenceVersionedIdentity.V2
                        || engine.status() == com.frauddetection.common.events.engine.FraudEngineStatus.AVAILABLE
                        || engine.riskLevel() != null
                        || engine.scoreBucket() != com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket.UNAVAILABLE)) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        try {
            validateProjectionReasonCodes(engine.reasonCodes(), device);
        } catch (IllegalArgumentException exception) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
    }

    private void validateProjectionReasonCodes(List<String> source, boolean device) {
        if (source == null) {
            return;
        }
        if (source.size() > 5) {
            throw new IllegalArgumentException("reasonCodes exceeds max size");
        }
        for (String reasonCode : source) {
            if (device) {
                if (reasonCode == null
                        || !EngineIntelligenceFeedbackDatasetSafety.DEVICE_ALLOWED_REASON_CODES.contains(reasonCode)) {
                    throw new IllegalArgumentException("DEVICE_REASON_CODE_ENGINE_MISMATCH");
                }
                continue;
            }
            String safeCode = EngineIntelligenceFeedbackDatasetSafety.requireMachineCode(reasonCode, "reasonCodes");
            if (EngineIntelligenceFeedbackDatasetSafety.DEVICE_EVIDENCE_REASON_CODES.contains(safeCode)) {
                throw new IllegalArgumentException("DEVICE_REASON_CODE_ENGINE_MISMATCH");
            }
        }
    }

    private void validateSignal(EngineIntelligenceDiagnosticSignalProjection signal, int contractVersion) {
        if (signal == null || signal.reasonCode() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        try {
            boolean device = EngineIntelligenceVersionedIdentity.DEVICE_PRIMARY_ENGINE_ID.equals(signal.engineId());
            if (device) {
                if (contractVersion != EngineIntelligenceVersionedIdentity.V2
                        || signal.engineType() != FraudEngineType.DEVICE_RISK
                        || signal.engineStatus() == com.frauddetection.common.events.engine.FraudEngineStatus.AVAILABLE
                        || signal.riskLevel() != null
                        || signal.scoreBucket()
                                != com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket.UNAVAILABLE
                        || !EngineIntelligenceFeedbackDatasetSafety.DEVICE_ALLOWED_REASON_CODES.contains(signal.reasonCode())) {
                    throw new IllegalArgumentException("INVALID_DEVICE_DIAGNOSTIC_SIGNAL");
                }
            } else {
                EngineIntelligenceFeedbackDatasetSafety.requireMachineCode(signal.reasonCode(), "diagnosticSignals");
            }
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
            List<String> diagnosticSignals,
            Integer engineIntelligenceContractVersion,
            com.frauddetection.common.events.engine.FraudEngineStatus deviceEngineStatus,
            com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket deviceScoreBucket,
            com.frauddetection.common.events.enums.RiskLevel deviceRiskLevel
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
                    List.of(),
                    null,
                    null,
                    null,
                    null
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
