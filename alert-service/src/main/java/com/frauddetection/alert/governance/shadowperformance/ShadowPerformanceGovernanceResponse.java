package com.frauddetection.alert.governance.shadowperformance;

import java.util.List;

public record ShadowPerformanceGovernanceResponse(
        String governanceStatus,
        List<String> approvedFor,
        boolean diagnosticOnly,
        boolean notProductionApproval,
        boolean notPromotionApproval,
        boolean notThresholdRecommendation,
        boolean notPaymentAuthorization,
        boolean notAutomaticDecisioning
) {
    static ShadowPerformanceGovernanceResponse from(ShadowPerformanceSummary.ShadowPerformanceGovernance governance) {
        return new ShadowPerformanceGovernanceResponse(
                governance.governanceStatus(),
                List.copyOf(governance.approvedFor()),
                governance.diagnosticOnly(),
                governance.notProductionApproval(),
                governance.notPromotionApproval(),
                governance.notThresholdRecommendation(),
                governance.notPaymentAuthorization(),
                governance.notAutomaticDecisioning()
        );
    }
}
