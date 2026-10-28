package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class EngineIntelligenceFeedbackDatasetRecordMapper {

    EngineIntelligenceFeedbackDatasetRecord map(
            EngineIntelligenceFeedbackDocument feedback,
            AlertDocument alert,
            EngineIntelligenceProjection projection
    ) {
        validateFeedbackSource(feedback);
        return mapValidatedFeedback(feedback, alert, projection);
    }

    EngineIntelligenceFeedbackDatasetRecord mapValidatedFeedback(
            EngineIntelligenceFeedbackDocument feedback,
            AlertDocument alert,
            EngineIntelligenceProjection projection
    ) {
        validateAlert(feedback, alert);
        EngineIntelligenceFeedbackDatasetLabelMapper.MappedLabel label =
                EngineIntelligenceFeedbackDatasetLabelMapper.map(alert.getAnalystDecision());
        EngineSignals signals = projection == null ? EngineSignals.missing() : signalsFrom(feedback, projection);
        try {
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
                    signals.reasonCodesByEngine(),
                    signals.diagnosticSignals(),
                    signals.engineIntelligenceContractVersion(),
                    signals.deviceEngineStatus(),
                    signals.deviceScoreBucket(),
                    signals.deviceRiskLevel()
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw projection == null ? corruptedFeedback() : corruptedProjection();
        }
    }

    void validateFeedbackSource(EngineIntelligenceFeedbackDocument feedback) {
        if (feedback == null) {
            throw corruptedFeedback();
        }
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
            EngineIntelligenceFeedbackDatasetSafety.evaluationRecordId(feedback.getFeedbackId());
            EngineIntelligenceFeedbackDatasetSafety.transactionReference(feedback.getTransactionId());
            EngineIntelligenceFeedbackDatasetSafety.copyMachineCodes(
                    feedback.getSelectedReasonCodes(),
                    "selectedReasonCodes",
                    5
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw corruptedFeedback();
        }
    }

    private void validateAlert(EngineIntelligenceFeedbackDocument feedback, AlertDocument alert) {
        if (alert == null
                || alert.getTransactionId() == null
                || !feedback.getTransactionId().equals(alert.getTransactionId())) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_ALERT_DATA
            );
        }
    }

    private EngineSignals signalsFrom(EngineIntelligenceFeedbackDocument feedback, EngineIntelligenceProjection projection) {
        if (projection.getTransactionId() == null
                || !feedback.getTransactionId().equals(projection.getTransactionId())
                || projection.getContractVersion() != FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION
                || projection.getGeneratedAt() == null
                || projection.getEngines() == null
                || projection.getDiagnosticSignals() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        EngineIntelligenceSummary summary = validatedSummaryFrom(projection);
        EngineValues ml = EngineValues.missing();
        EngineValues rules = EngineValues.missing();
        EngineValues device = EngineValues.missing();
        List<String> reasonCodes = new ArrayList<>();
        Map<String, List<String>> reasonCodesByEngine = new LinkedHashMap<>();
        for (EngineIntelligenceEngineResult engine : summary.engines()) {
            reasonCodes.addAll(engine.reasonCodes());
            reasonCodesByEngine.put(engine.engineId(), engine.reasonCodes());
            if (engine.engineType() == FraudEngineType.ML_MODEL) {
                ml = new EngineValues(engine.status(), engine.scoreBucket(), engine.riskLevel());
            }
            if (engine.engineType() == FraudEngineType.RULES) {
                rules = new EngineValues(engine.status(), engine.scoreBucket(), engine.riskLevel());
            }
            if (FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID.equals(engine.engineId())) {
                device = new EngineValues(engine.status(), engine.scoreBucket(), engine.riskLevel());
            }
        }
        List<String> diagnosticSignals = summary.diagnosticSignals().stream()
                .map(EngineIntelligenceDiagnosticSignal::reasonCode)
                .toList();
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
                reasonCodesByEngine,
                diagnosticSignals,
                projection.getContractVersion(),
                device.status(),
                device.scoreBucket(),
                device.riskLevel()
        );
    }

    private EngineIntelligenceSummary validatedSummaryFrom(EngineIntelligenceProjection projection) {
        try {
            List<EngineIntelligenceEngineResult> engines = projection.getEngines().stream()
                    .map(engine -> new EngineIntelligenceEngineResult(
                            engine.engineId(),
                            engine.engineType(),
                            engine.status(),
                            engine.riskLevel(),
                            engine.scoreBucket(),
                            engine.reasonCodes(),
                            engine.modelIdentity()
                    ))
                    .toList();
            EngineIntelligenceComparison comparison = new EngineIntelligenceComparison(
                    projection.getComparisonType(),
                    projection.getComparedEngineIds(),
                    projection.getComparisonStatus(),
                    projection.getRiskMismatchStatus(),
                    projection.getScoreDeltaBucket()
            );
            List<EngineIntelligenceDiagnosticSignal> diagnosticSignals = projection.getDiagnosticSignals().stream()
                    .map(signal -> new EngineIntelligenceDiagnosticSignal(
                            signal.engineId(),
                            signal.engineType(),
                            signal.engineStatus(),
                            signal.signalCategory(),
                            signal.riskLevel(),
                            signal.scoreBucket(),
                            signal.reasonCode()
                    ))
                    .toList();
            List<EngineIntelligenceWarningSummary> warnings = projection.getWarnings().stream()
                    .map(warning -> new EngineIntelligenceWarningSummary(
                            warning.warningCode(),
                            warning.count()
                    ))
                    .toList();
            EngineIntelligenceSummary summary = new EngineIntelligenceSummary(
                    projection.getContractVersion(),
                    projection.getGeneratedAt(),
                    engines,
                    comparison,
                    diagnosticSignals,
                    warnings
            );
            return new EngineIntelligenceProjectionPolicy().validatedCopy(summary);
        } catch (RuntimeException exception) {
            throw corruptedProjection();
        }
    }

    private CorruptedDatasetSourceException corruptedProjection() {
        return new CorruptedDatasetSourceException(
                EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
        );
    }

    private CorruptedDatasetSourceException corruptedFeedback() {
        return new CorruptedDatasetSourceException(
                EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK
        );
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
            Map<String, List<String>> reasonCodesByEngine,
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
                    Map.of(),
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
