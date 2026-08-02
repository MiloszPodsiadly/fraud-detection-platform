package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class EngineIntelligenceSummarySemanticPolicy {
    private static final String RULES = FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID;
    private static final String ML = FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID;
    private static final String VELOCITY = FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID;

    private EngineIntelligenceSummarySemanticPolicy() {
    }

    static void validate(
            List<EngineIntelligenceEngineResult> engines,
            EngineIntelligenceComparison comparison,
            List<EngineIntelligenceDiagnosticSignal> diagnosticSignals
    ) {
        Map<String, EngineIntelligenceEngineResult> byEngineId = requireMandatoryEngineSet(engines);
        requireComparisonCoherence(comparison, byEngineId);
        requireDiagnosticSignalCoherence(diagnosticSignals, byEngineId);
    }

    private static Map<String, EngineIntelligenceEngineResult> requireMandatoryEngineSet(
            List<EngineIntelligenceEngineResult> engines
    ) {
        if (engines.size() != 2 && engines.size() != 3) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_REQUIRED_ENGINES_MISSING");
        }
        Map<String, EngineIntelligenceEngineResult> byEngineId = new HashMap<>();
        for (EngineIntelligenceEngineResult engine : engines) {
            byEngineId.put(engine.engineId(), engine);
        }
        if (!byEngineId.containsKey(RULES) || !byEngineId.containsKey(ML)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_REQUIRED_ENGINES_MISSING");
        }
        if (engines.size() == 3 && !byEngineId.containsKey(VELOCITY)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNKNOWN_ENGINE_ID");
        }
        return Map.copyOf(byEngineId);
    }

    private static void requireComparisonCoherence(
            EngineIntelligenceComparison comparison,
            Map<String, EngineIntelligenceEngineResult> engines
    ) {
        Objects.requireNonNull(comparison, "comparison is required");
        for (String engineId : comparison.comparedEngineIds()) {
            if (!engines.containsKey(engineId)) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_ENGINE_ABSENT");
            }
        }
        EngineIntelligenceEngineResult rules = engines.get(RULES);
        EngineIntelligenceEngineResult ml = engines.get(ML);
        if (!isAvailable(rules) || !isAvailable(ml)) {
            requireOperationalComparison(comparison, rules, ml);
            return;
        }
        EngineIntelligenceRiskMismatchStatus expectedRiskMismatch = riskMismatch(rules.riskLevel(), ml.riskLevel());
        if (comparison.riskMismatchStatus() != expectedRiskMismatch) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_RISK_MISMATCH_INCONSISTENT");
        }
        EngineIntelligenceAgreementStatus expectedAgreement = agreementFor(expectedRiskMismatch);
        if (comparison.agreementStatus() != expectedAgreement) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_AGREEMENT_INCONSISTENT");
        }
        if (comparison.scoreDeltaBucket() == EngineIntelligenceScoreDeltaBucket.UNAVAILABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_DELTA_INCONSISTENT");
        }
        if (!deltaBucketCanDescribe(rules.scoreBucket(), ml.scoreBucket(), comparison.scoreDeltaBucket())) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_DELTA_INCONSISTENT");
        }
    }

    private static void requireOperationalComparison(
            EngineIntelligenceComparison comparison,
            EngineIntelligenceEngineResult rules,
            EngineIntelligenceEngineResult ml
    ) {
        if (comparison.riskMismatchStatus() != EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_OPERATIONAL_INCONSISTENT");
        }
        if (comparison.scoreDeltaBucket() != EngineIntelligenceScoreDeltaBucket.UNAVAILABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_OPERATIONAL_INCONSISTENT");
        }
        EngineIntelligenceAgreementStatus expected = isAvailable(rules)
                ? EngineIntelligenceAgreementStatus.PARTIAL
                : EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE;
        if (!isAvailable(ml) && comparison.agreementStatus() != expected) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_OPERATIONAL_INCONSISTENT");
        }
        if (isAvailable(ml) && comparison.agreementStatus() != EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_OPERATIONAL_INCONSISTENT");
        }
    }

    private static void requireDiagnosticSignalCoherence(
            List<EngineIntelligenceDiagnosticSignal> signals,
            Map<String, EngineIntelligenceEngineResult> engines
    ) {
        for (EngineIntelligenceDiagnosticSignal signal : signals) {
            EngineIntelligenceEngineResult engine = engines.get(signal.engineId());
            if (engine == null) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DIAGNOSTIC_ENGINE_ABSENT");
            }
            if (signal.engineType() != engine.engineType() || signal.engineStatus() != engine.status()) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DIAGNOSTIC_ENGINE_INCONSISTENT");
            }
            if (isAvailable(engine)) {
                requireFraudSignalCoherence(signal, engine);
            } else {
                requireOperationalSignalCoherence(signal, engine);
            }
            if (!engine.reasonCodes().contains(signal.reasonCode())) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DIAGNOSTIC_REASON_INCONSISTENT");
            }
        }
    }

    private static void requireFraudSignalCoherence(
            EngineIntelligenceDiagnosticSignal signal,
            EngineIntelligenceEngineResult engine
    ) {
        if (signal.signalCategory() != EngineIntelligenceSignalCategory.FRAUD_SIGNAL) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNAL_CATEGORY_INCONSISTENT");
        }
        if (signal.riskLevel() != engine.riskLevel() || signal.scoreBucket() != engine.scoreBucket()) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNAL_INCONSISTENT");
        }
    }

    private static void requireOperationalSignalCoherence(
            EngineIntelligenceDiagnosticSignal signal,
            EngineIntelligenceEngineResult engine
    ) {
        if (signal.signalCategory() != EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNAL_CATEGORY_INCONSISTENT");
        }
        if (signal.riskLevel() != null || signal.scoreBucket() != EngineIntelligenceScoreBucket.UNAVAILABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNAL_INCONSISTENT");
        }
    }

    private static EngineIntelligenceRiskMismatchStatus riskMismatch(RiskLevel rules, RiskLevel ml) {
        int distance = Math.abs(riskSeverity(rules) - riskSeverity(ml));
        if (distance == 0) {
            return EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL;
        }
        if (distance == 1) {
            return EngineIntelligenceRiskMismatchStatus.ADJACENT_RISK_LEVEL;
        }
        return EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH;
    }

    private static EngineIntelligenceAgreementStatus agreementFor(EngineIntelligenceRiskMismatchStatus mismatch) {
        return switch (mismatch) {
            case SAME_RISK_LEVEL -> EngineIntelligenceAgreementStatus.AGREEMENT;
            case ADJACENT_RISK_LEVEL -> EngineIntelligenceAgreementStatus.ADJACENT_RISK_VARIANCE;
            case MATERIAL_RISK_MISMATCH -> EngineIntelligenceAgreementStatus.DISAGREEMENT;
            case NOT_COMPARABLE -> EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA;
        };
    }

    private static int riskSeverity(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case CRITICAL -> 3;
        };
    }

    private static boolean deltaBucketCanDescribe(
            EngineIntelligenceScoreBucket rules,
            EngineIntelligenceScoreBucket ml,
            EngineIntelligenceScoreDeltaBucket delta
    ) {
        if (delta == EngineIntelligenceScoreDeltaBucket.UNAVAILABLE) {
            return false;
        }
        int distance = Math.abs(scoreBucketSeverity(rules) - scoreBucketSeverity(ml));
        return switch (distance) {
            case 0 -> delta != EngineIntelligenceScoreDeltaBucket.LARGE;
            case 1 -> delta != EngineIntelligenceScoreDeltaBucket.NONE;
            case 2 -> delta == EngineIntelligenceScoreDeltaBucket.MEDIUM
                    || delta == EngineIntelligenceScoreDeltaBucket.LARGE;
            case 3 -> delta == EngineIntelligenceScoreDeltaBucket.LARGE;
            default -> false;
        };
    }

    private static int scoreBucketSeverity(EngineIntelligenceScoreBucket bucket) {
        return switch (bucket) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case VERY_HIGH -> 3;
            case NONE, UNAVAILABLE -> -1;
        };
    }

    private static boolean isAvailable(EngineIntelligenceEngineResult engine) {
        return engine.status() == FraudEngineStatus.AVAILABLE;
    }
}
