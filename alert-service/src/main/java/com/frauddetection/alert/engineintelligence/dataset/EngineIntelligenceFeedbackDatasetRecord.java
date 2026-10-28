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
        Objects.requireNonNull(reasonCodes, "reasonCodes is required");
        Objects.requireNonNull(diagnosticSignals, "diagnosticSignals is required");
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
        validateProjectionSemantics(
                projectionStatus,
                engineIntelligenceContractVersion,
                agreementStatus,
                riskMismatchStatus,
                scoreDeltaBucket,
                mlEngineStatus,
                mlScoreBucket,
                mlRiskLevel,
                rulesEngineStatus,
                rulesScoreBucket,
                rulesRiskLevel,
                reasonCodesByEngine,
                deviceEngineStatus,
                deviceScoreBucket,
                deviceRiskLevel
        );
    }

    private static void validateProjectionSemantics(
            EngineIntelligenceFeedbackDatasetProjectionStatus projectionStatus,
            Integer engineIntelligenceContractVersion,
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            FraudEngineStatus mlEngineStatus,
            EngineIntelligenceScoreBucket mlScoreBucket,
            RiskLevel mlRiskLevel,
            FraudEngineStatus rulesEngineStatus,
            EngineIntelligenceScoreBucket rulesScoreBucket,
            RiskLevel rulesRiskLevel,
            Map<String, List<String>> reasonCodesByEngine,
            FraudEngineStatus deviceEngineStatus,
            EngineIntelligenceScoreBucket deviceScoreBucket,
            RiskLevel deviceRiskLevel
    ) {
        if (deviceRiskLevel != null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_DEVICE_RISK_LEVEL_INVALID");
        }
        boolean deviceGroupPresent = reasonCodesByEngine.containsKey(FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID);
        if (deviceGroupPresent != (deviceEngineStatus != null)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_DEVICE_STATE_INVALID");
        }
        if (deviceEngineStatus == null) {
            if (deviceScoreBucket != null) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_DEVICE_STATE_INVALID");
            }
        } else if (deviceEngineStatus == FraudEngineStatus.AVAILABLE
                || deviceScoreBucket != EngineIntelligenceScoreBucket.UNAVAILABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_DEVICE_STATE_INVALID");
        }

        if (projectionStatus == EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING) {
            if (engineIntelligenceContractVersion != null
                    || agreementStatus != null
                    || riskMismatchStatus != null
                    || scoreDeltaBucket != null
                    || mlEngineStatus != null
                    || mlScoreBucket != null
                    || mlRiskLevel != null
                    || rulesEngineStatus != null
                    || rulesScoreBucket != null
                    || rulesRiskLevel != null
                    || deviceEngineStatus != null
                    || deviceScoreBucket != null) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_MISSING_PROJECTION_HAS_ENGINE_STATE");
            }
            return;
        }

        if (engineIntelligenceContractVersion == null
                || engineIntelligenceContractVersion != FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_CONTRACT_VERSION_UNSUPPORTED");
        }
        if (!reasonCodesByEngine.containsKey(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID)
                || !reasonCodesByEngine.containsKey(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_REQUIRED_ENGINES_MISSING");
        }
        validateEngineProfile(mlEngineStatus, mlScoreBucket, mlRiskLevel);
        validateEngineProfile(rulesEngineStatus, rulesScoreBucket, rulesRiskLevel);
        validateComparison(
                agreementStatus,
                riskMismatchStatus,
                scoreDeltaBucket,
                mlEngineStatus,
                mlScoreBucket,
                mlRiskLevel,
                rulesEngineStatus,
                rulesScoreBucket,
                rulesRiskLevel
        );
    }

    private static void validateEngineProfile(
            FraudEngineStatus status,
            EngineIntelligenceScoreBucket scoreBucket,
            RiskLevel riskLevel
    ) {
        if (status == null || scoreBucket == null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_REQUIRED_ENGINE_STATE_MISSING");
        }
        if (status == FraudEngineStatus.AVAILABLE) {
            if (riskLevel == null || !scoreBucket.isUsableAvailableBucket()) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_AVAILABLE_ENGINE_STATE_INVALID");
            }
        } else if (riskLevel != null || scoreBucket != EngineIntelligenceScoreBucket.UNAVAILABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_OPERATIONAL_ENGINE_STATE_INVALID");
        }
    }

    private static void validateComparison(
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            FraudEngineStatus mlEngineStatus,
            EngineIntelligenceScoreBucket mlScoreBucket,
            RiskLevel mlRiskLevel,
            FraudEngineStatus rulesEngineStatus,
            EngineIntelligenceScoreBucket rulesScoreBucket,
            RiskLevel rulesRiskLevel
    ) {
        Objects.requireNonNull(agreementStatus, "agreementStatus is required for an available projection");
        Objects.requireNonNull(riskMismatchStatus, "riskMismatchStatus is required for an available projection");
        Objects.requireNonNull(scoreDeltaBucket, "scoreDeltaBucket is required for an available projection");
        if (mlEngineStatus != FraudEngineStatus.AVAILABLE || rulesEngineStatus != FraudEngineStatus.AVAILABLE) {
            EngineIntelligenceAgreementStatus expectedAgreement = rulesEngineStatus == FraudEngineStatus.AVAILABLE
                    ? EngineIntelligenceAgreementStatus.PARTIAL
                    : EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE;
            if (riskMismatchStatus != EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE
                    || scoreDeltaBucket != EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                    || agreementStatus != expectedAgreement) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_OPERATIONAL_COMPARISON_INVALID");
            }
            return;
        }

        int riskDistance = Math.abs(riskSeverity(rulesRiskLevel) - riskSeverity(mlRiskLevel));
        EngineIntelligenceRiskMismatchStatus expectedMismatch = switch (riskDistance) {
            case 0 -> EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL;
            case 1 -> EngineIntelligenceRiskMismatchStatus.ADJACENT_RISK_LEVEL;
            default -> EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH;
        };
        EngineIntelligenceAgreementStatus expectedAgreement = switch (expectedMismatch) {
            case SAME_RISK_LEVEL -> EngineIntelligenceAgreementStatus.AGREEMENT;
            case ADJACENT_RISK_LEVEL -> EngineIntelligenceAgreementStatus.ADJACENT_RISK_VARIANCE;
            case MATERIAL_RISK_MISMATCH -> EngineIntelligenceAgreementStatus.DISAGREEMENT;
            case NOT_COMPARABLE -> throw new IllegalStateException("available risk levels cannot be incomparable");
        };
        if (riskMismatchStatus != expectedMismatch || agreementStatus != expectedAgreement
                || scoreDeltaBucket == EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                || !scoreDeltaCanDescribe(rulesScoreBucket, mlScoreBucket, scoreDeltaBucket)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DATASET_COMPARISON_INVALID");
        }
    }

    private static int riskSeverity(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case CRITICAL -> 3;
        };
    }

    private static boolean scoreDeltaCanDescribe(
            EngineIntelligenceScoreBucket rules,
            EngineIntelligenceScoreBucket ml,
            EngineIntelligenceScoreDeltaBucket delta
    ) {
        int distance = Math.abs(scoreSeverity(rules) - scoreSeverity(ml));
        return switch (distance) {
            case 0 -> delta != EngineIntelligenceScoreDeltaBucket.LARGE;
            case 1 -> delta != EngineIntelligenceScoreDeltaBucket.NONE;
            case 2 -> delta == EngineIntelligenceScoreDeltaBucket.MEDIUM
                    || delta == EngineIntelligenceScoreDeltaBucket.LARGE;
            case 3 -> delta == EngineIntelligenceScoreDeltaBucket.LARGE;
            default -> false;
        };
    }

    private static int scoreSeverity(EngineIntelligenceScoreBucket scoreBucket) {
        return switch (scoreBucket) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case VERY_HIGH -> 3;
            case NONE, UNAVAILABLE -> -1;
        };
    }

}
