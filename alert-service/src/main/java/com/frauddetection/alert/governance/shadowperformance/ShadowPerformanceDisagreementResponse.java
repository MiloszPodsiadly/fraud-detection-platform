package com.frauddetection.alert.governance.shadowperformance;

public record ShadowPerformanceDisagreementResponse(
        int rulesHighMlHigh,
        int rulesHighMlLowOrMedium,
        int rulesLowOrMediumMlHigh,
        int rulesLowOrMediumMlLowOrMedium,
        int rulesMissingMlPresent,
        int mlMissingRulesPresent,
        int bothMissing,
        int notEvaluationEligibleExcluded
) {
    static ShadowPerformanceDisagreementResponse from(ShadowPerformanceSummary.ShadowPerformanceDisagreement disagreement) {
        return new ShadowPerformanceDisagreementResponse(
                disagreement.rulesHighMlHigh(),
                disagreement.rulesHighMlLowOrMedium(),
                disagreement.rulesLowOrMediumMlHigh(),
                disagreement.rulesLowOrMediumMlLowOrMedium(),
                disagreement.rulesMissingMlPresent(),
                disagreement.mlMissingRulesPresent(),
                disagreement.bothMissing(),
                disagreement.notEvaluationEligibleExcluded()
        );
    }
}
