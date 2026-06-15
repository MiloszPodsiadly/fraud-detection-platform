package com.frauddetection.alert.governance.promotionreviewreadiness;

final class PromotionReviewReadinessReportContract {

    static final String REPORT_TYPE = "PROMOTION_REVIEW_READINESS_REPORT_V1";
    static final String REPORT_VERSION = "1.0";
    static final String GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY";
    static final String REQUIRED_BANNER = "Promotion review readiness is an offline diagnostic aid only. It is not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.";

    private PromotionReviewReadinessReportContract() {
    }
}
