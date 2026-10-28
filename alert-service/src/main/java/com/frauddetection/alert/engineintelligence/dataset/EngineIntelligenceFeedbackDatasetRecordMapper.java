package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceReasonCodePolicy;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                signals.reasonCodesByEngine(),
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
                || projection.getContractVersion() != FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION
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
        Map<String, List<String>> reasonCodesByEngine = new LinkedHashMap<>();
        validateEngineProfile(projection.getEngines(), projection.getContractVersion());
        for (EngineIntelligenceEngineProjection engine : projection.getEngines()) {
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
        List<String> diagnosticSignals = new ArrayList<>();
        if (projection.getDiagnosticSignals().size() > EngineIntelligenceFeedbackDatasetRecord.MAX_DIAGNOSTIC_SIGNALS) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
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
                reasonCodesByEngine,
                diagnosticSignals,
                projection.getContractVersion(),
                device.status(),
                device.scoreBucket(),
                device.riskLevel()
        );
    }

    private void validateEngineProfile(List<EngineIntelligenceEngineProjection> engines, int contractVersion) {
        if (engines == null
                || engines.size() < 2
                || engines.size() > FraudEngineIdentityContract.maxEngineIntelligenceEngines()) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        Set<String> engineIds = new HashSet<>();
        int previousEngineOrder = -1;
        for (EngineIntelligenceEngineProjection engine : engines) {
            validateEngine(engine, contractVersion);
            int engineOrder = FraudEngineIdentityContract.orderOf(engine.engineId());
            if (engineOrder <= previousEngineOrder || !engineIds.add(engine.engineId())) {
                throw new CorruptedDatasetSourceException(
                        EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
                );
            }
            previousEngineOrder = engineOrder;
        }
        if (!engineIds.contains(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID)
                || !engineIds.contains(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID)) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
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
        boolean device = FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID.equals(engine.engineId());
        if (!FraudEngineIdentityContract.engineOrder().contains(engine.engineId())
                || !FraudEngineIdentityContract.hasExpectedType(engine.engineId(), engine.engineType())
                || device && (contractVersion != FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION
                        || engine.status() == com.frauddetection.common.events.engine.FraudEngineStatus.AVAILABLE
                        || engine.riskLevel() != null
                        || engine.scoreBucket() != com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket.UNAVAILABLE)) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        try {
            validateProjectionReasonCodes(engine.reasonCodes(), engine.engineId());
        } catch (IllegalArgumentException exception) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
    }

    private void validateProjectionReasonCodes(List<String> source, String engineId) {
        if (source == null) {
            return;
        }
        if (source.size() > EngineIntelligenceProjectionPolicy.MAX_REASON_CODES_PER_ENGINE) {
            throw new IllegalArgumentException("reasonCodes exceeds max size");
        }
        for (String reasonCode : source) {
            if (reasonCode == null || !EngineIntelligenceReasonCodePolicy.isAllowedForEngine(engineId, reasonCode)) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED_FOR_ENGINE");
            }
            EngineIntelligenceFeedbackDatasetSafety.requireCanonicalReasonCode(reasonCode, "reasonCodes");
        }
    }

    private void validateSignal(EngineIntelligenceDiagnosticSignalProjection signal, int contractVersion) {
        if (signal == null || signal.reasonCode() == null) {
            throw new CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
        }
        try {
            if (!FraudEngineIdentityContract.engineOrder().contains(signal.engineId())
                    || !FraudEngineIdentityContract.hasExpectedType(signal.engineId(), signal.engineType())) {
                throw new IllegalArgumentException("INVALID_DIAGNOSTIC_SIGNAL_ENGINE_IDENTITY");
            }
            boolean device = FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID.equals(signal.engineId());
            if (device) {
                if (contractVersion != FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION
                        || signal.engineStatus() == FraudEngineStatus.AVAILABLE
                        || signal.riskLevel() != null
                        || signal.scoreBucket()
                                != EngineIntelligenceScoreBucket.UNAVAILABLE) {
                    throw new IllegalArgumentException("INVALID_DEVICE_DIAGNOSTIC_SIGNAL");
                }
            }
            if (!EngineIntelligenceReasonCodePolicy.isAllowedForEngine(signal.engineId(), signal.reasonCode())) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED_FOR_ENGINE");
            }
            EngineIntelligenceFeedbackDatasetSafety.requireCanonicalReasonCode(signal.reasonCode(), "diagnosticSignals");
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
