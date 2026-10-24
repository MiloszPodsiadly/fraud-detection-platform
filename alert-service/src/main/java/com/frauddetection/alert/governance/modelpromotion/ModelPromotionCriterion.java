package com.frauddetection.alert.governance.modelpromotion;

import java.util.Set;

public enum ModelPromotionCriterion {

    STATISTICALLY_MEANINGFUL_SAMPLE(
            "EXACT_MODEL_SHADOW_PERFORMANCE.evaluationSummary.population_and_classBalance",
            "Bounded evaluated records with both analyst-feedback label classes",
            "Exact-artifact bounded analyst-feedback cohort",
            "recordsEvaluated and positive/negative class counts",
            Set.of("minimumEvaluatedRecords", "minimumPositiveLabels", "minimumNegativeLabels")
    ),
    PRECISION_AT_ALERT_REVIEW_BUDGET(
            "MATCHED_RULES_AND_ML_ALERT_BUDGET_EVALUATION",
            "ML precision and Rules precision at the same review budget on the same population",
            "Matched labeled records scored by both Rules and the exact ML artifact",
            "same requested and actual review budget for both scorers",
            Set.of("alertReviewBudget", "minimumMlPrecision", "minimumDeltaVsRules")
    ),
    FALSE_POSITIVE_RATE(
            "EXACT_MODEL_SHADOW_PERFORMANCE.evaluationSummary.supportedMetrics.mlPredictionMetrics",
            "falsePositive / (falsePositive + trueNegative)",
            "Exact-artifact bounded analyst-feedback cohort",
            "analyst-feedback negative class",
            Set.of("maximumRate")
    ),
    RULES_VS_ML_DISAGREEMENT(
            "EXACT_MODEL_SHADOW_PERFORMANCE.evaluationSummary.supportedMetrics.rulesVsMlDisagreement",
            "records with differing binary Rules and ML classifications / records compared",
            "Records with exact ML evidence and available Rules evidence",
            "recordsCompared",
            Set.of("maximumRate")
    ),
    CRITICAL_SEGMENT_REGRESSIONS(
            "MATCHED_SEGMENT_REGRESSION_EVALUATION",
            "critical segments breaching their approved regression bounds",
            "Policy-defined identifiable segments on a matched evaluation population",
            "segments evaluated under the same segment definitions",
            Set.of("maximumCriticalRegressions", "minimumSegmentsEvaluated")
    ),
    EXACT_MODEL_LATENCY_STABILITY(
            "EXACT_MODEL_SHADOW_PERFORMANCE.operationalDiagnostics.mlLatencyP95",
            "p95 inference latency for the exact model artifact",
            "Operational exact-artifact inference observations in the evaluation window",
            "valid exact-artifact latency observations",
            Set.of("maximumMilliseconds", "minimumSampleSize")
    ),
    EXACT_MODEL_FALLBACK_RATE(
            "EXACT_MODEL_SHADOW_PERFORMANCE.operationalDiagnostics.fallbackRate",
            "exact-artifact fallback count / eligible exact-artifact scoring attempts",
            "Operational exact-artifact scoring attempts in the evaluation window",
            "eligible exact-artifact scoring attempts",
            Set.of("maximumRate", "minimumDenominator")
    ),
    COMPLETE_EVALUATION_PROVENANCE(
            "MODEL_PROMOTION_EVIDENCE_BUNDLE.trust_and_source_evaluation",
            "all required report, manifest, source-dataset and window provenance is present and verified",
            "Exact Model Card and Shadow Performance evidence bundle",
            "all mandatory provenance assertions",
            Set.of()
    ),
    EXACT_SHA_MODEL_CARD(
            "MODEL_CARD.subject_and_modelArtifactVerification",
            "Model Card subject and verified artifact digest equal the assessed exact model identity",
            "The exact immutable model artifact",
            "one verified Model Card for the assessed artifact",
            Set.of()
    ),
    IDENTITY_AND_LINEAGE_INTEGRITY(
            "EXACT_MODEL_SHADOW_PERFORMANCE.evaluationSummary.population_and_lineagePolicy",
            "no unresolved exact-model identity mismatch or critical lineage exclusion",
            "Source rows considered for the exact-artifact evaluation",
            "recordsConsidered and lineage exclusion counters",
            Set.of("maximumCriticalLineageGaps")
    );

    private final String evidenceSource;
    private final String metricSemantics;
    private final String populationDefinition;
    private final String denominatorDefinition;
    private final Set<String> requiredThresholdKeys;

    ModelPromotionCriterion(
            String evidenceSource,
            String metricSemantics,
            String populationDefinition,
            String denominatorDefinition,
            Set<String> requiredThresholdKeys
    ) {
        this.evidenceSource = evidenceSource;
        this.metricSemantics = metricSemantics;
        this.populationDefinition = populationDefinition;
        this.denominatorDefinition = denominatorDefinition;
        this.requiredThresholdKeys = Set.copyOf(requiredThresholdKeys);
    }

    public String evidenceSource() {
        return evidenceSource;
    }

    public String metricSemantics() {
        return metricSemantics;
    }

    public String populationDefinition() {
        return populationDefinition;
    }

    public String denominatorDefinition() {
        return denominatorDefinition;
    }

    public Set<String> requiredThresholdKeys() {
        return requiredThresholdKeys;
    }
}
