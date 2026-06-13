package com.frauddetection.alert.governance.shadowperformance;

final class ShadowPerformanceSummaryContract {

    static final String REQUIRED_BANNER = "Shadow performance metrics are offline diagnostics only. "
            + "They are not model promotion approval, not threshold recommendation, "
            + "not production decisioning approval, not payment authorization, "
            + "not automatic approve / decline / block logic, or not analyst recommendation logic.";

    private ShadowPerformanceSummaryContract() {
    }
}
