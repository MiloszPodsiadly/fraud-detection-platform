package com.frauddetection.alert.governance.promotionreviewreadiness;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
class PromotionReviewReadinessReportValidator {

    private static final int MAX_COUNT_VALUE = 500;
    private static final Pattern MACHINE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final Set<String> READINESS_STATUSES = Set.of("INSUFFICIENT_DATA", "NOT_REVIEWABLE", "REVIEWABLE");
    private static final Set<String> CHECK_STATUSES = Set.of("PASS", "WARN", "FAIL", "NOT_APPLICABLE");
    private static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH");
    private static final Set<String> CHECK_NAMES = Set.of(
            "CURRENT_SUMMARY_PRESENT",
            "CURRENT_SUMMARY_VERSION_SUPPORTED",
            "MODEL_CARD_PRESENT",
            "MODEL_CARD_VERSION_SUPPORTED",
            "GOVERNANCE_STATUS_DIAGNOSTIC_ONLY",
            "GOVERNANCE_MODES_COMPARE_AND_SHADOW",
            "NOT_PRODUCTION_APPROVAL_TRUE",
            "NOT_PROMOTION_APPROVAL_TRUE",
            "NOT_THRESHOLD_RECOMMENDATION_TRUE",
            "NOT_PAYMENT_AUTHORIZATION_TRUE",
            "NOT_AUTOMATIC_DECISIONING_TRUE",
            "EVALUATION_REPORT_TYPE_SUPPORTED",
            "METRIC_BASIS_SUPPORTED",
            "DATASET_TIME_BASIS_SUPPORTED",
            "DEDUPLICATION_POLICY_SUPPORTED",
            "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS",
            "METRICS_PRESENT",
            "DISAGREEMENT_SUMMARY_PRESENT",
            "WARNINGS_PRESENT",
            "LIMITATIONS_PRESENT"
    );
    private static final Set<String> SAFE_LIMITATIONS = Set.of(
            "OFFLINE_DIAGNOSTIC_AID_ONLY",
            "HUMAN_REVIEW_START_ONLY",
            "DOES_NOT_RECOMMEND_THRESHOLDS",
            "DOES_NOT_AUTHORIZE_PAYMENTS",
            "DOES_NOT_CHANGE_SCORING"
    );
    private static final Set<String> FORBIDDEN_TERMS = Set.of(
            "evaluationrecordid",
            "transactionreference",
            "customerid",
            "accountid",
            "cardid",
            "deviceid",
            "merchantid",
            "analystid",
            "rawpayload",
            "rawfeaturevector",
            "rawmlrequest",
            "rawmlresponse",
            "groundtruth",
            "traininglabel",
            "finaldecision",
            "promotionapproved",
            "approvedforpromotion",
            "promoted",
            "readyforproduction",
            "deployable",
            "recommendedthreshold",
            "thresholdrecommendation",
            "paymentauthorized",
            "autoapprove",
            "autodecline",
            "blocktransaction",
            "analystrecommendation"
    );

    void validate(PromotionReviewReadinessReport report) {
        require(report != null, "report is missing");
        require(PromotionReviewReadinessReportContract.REPORT_TYPE.equals(report.reportType()), "reportType is unsupported");
        require(PromotionReviewReadinessReportContract.REPORT_VERSION.equals(report.reportVersion()), "reportVersion is unsupported");
        instant(report.generatedAt(), "generatedAt");
        require(PromotionReviewReadinessReportContract.GOVERNANCE_STATUS.equals(report.governanceStatus()), "governanceStatus is unsupported");
        require(READINESS_STATUSES.contains(report.readinessStatus()), "readinessStatus is unsupported");
        require(!PromotionReviewReadinessReportContract.GOVERNANCE_STATUS.equals(report.readinessStatus()),
                "DIAGNOSTIC_ONLY is governanceStatus, not readinessStatus");
        require(report.diagnosticOnly(), "diagnosticOnly must be true");
        require(report.notPromotionApproval(), "notPromotionApproval must be true");
        require(report.notThresholdRecommendation(), "notThresholdRecommendation must be true");
        require(report.notProductionDecisioning(), "notProductionDecisioning must be true");
        require(report.notPaymentAuthorization(), "notPaymentAuthorization must be true");
        require(report.notAutomaticDecisioning(), "notAutomaticDecisioning must be true");
        require(report.notAnalystRecommendation(), "notAnalystRecommendation must be true");
        validateInputs(report.inputs());
        validateChecks(report.checks());
        validateMachineCodes(report.reasonCodes(), 20, "reasonCodes");
        validateMachineCodes(report.warnings(), 20, "warnings");
        validateMachineCodes(report.limitations(), 20, "limitations");
        require(Set.copyOf(report.limitations()).containsAll(SAFE_LIMITATIONS), "limitations missing diagnostic non-goals");
        require(PromotionReviewReadinessReportContract.REQUIRED_BANNER.equals(report.banner()), "banner is unsupported");
    }

    private void validateInputs(PromotionReviewReadinessReport.PromotionReviewReadinessInputs inputs) {
        require(inputs != null, "inputs is missing");
        validateSummaryInput(inputs.shadowPerformanceSummary());
        boundedCount(inputs.minimumDiagnosticEvidenceRecords(), "minimumDiagnosticEvidenceRecords");
        require(inputs.minimumDiagnosticEvidenceRecords() > 0, "minimumDiagnosticEvidenceRecords must be positive");
        boundedCount(inputs.recordsAcceptedForEvaluation(), "recordsAcceptedForEvaluation");
    }

    private void validateSummaryInput(PromotionReviewReadinessReport.ShadowPerformanceSummaryInput input) {
        require(input != null, "shadowPerformanceSummary input is missing");
        require(input.present(), "shadowPerformanceSummary.present must be true");
        require("SHADOW_PERFORMANCE_SUMMARY_V1".equals(input.summaryType()), "summaryType is unsupported");
        require("1.0".equals(input.summaryVersion()), "summaryVersion is unsupported");
        instant(input.generatedAt(), "shadowPerformanceSummary.generatedAt");
    }

    private void validateChecks(List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks) {
        require(checks != null && !checks.isEmpty(), "checks are missing");
        require(checks.size() <= CHECK_NAMES.size(), "too many checks");
        for (PromotionReviewReadinessReport.PromotionReviewReadinessCheck check : checks) {
            require(check != null, "check is missing");
            machineCode(check.name(), "check.name");
            require(CHECK_NAMES.contains(check.name()), "check.name is unsupported");
            machineCode(check.status(), "check.status");
            require(CHECK_STATUSES.contains(check.status()), "check.status is unsupported");
            machineCode(check.severity(), "check.severity");
            require(SEVERITIES.contains(check.severity()), "check.severity is unsupported");
        }
    }

    private void validateMachineCodes(List<String> values, int maxItems, String field) {
        require(values != null, field + " is missing");
        require(values.size() <= maxItems, field + " has too many items");
        for (String value : values) {
            machineCode(value, field);
        }
    }

    private void machineCode(String value, String field) {
        safeString(value, field);
        require(MACHINE_CODE_PATTERN.matcher(value).matches(), field + " must be a machine-code string");
        rejectForbidden(value, field);
    }

    private void safeString(String value, String field) {
        require(value != null && !value.isBlank(), field + " is required");
        require(value.length() <= 512, field + " is too long");
        rejectForbidden(value, field);
    }

    private void instant(String value, String field) {
        safeString(value, field);
        try {
            Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new PromotionReviewReadinessReportValidationException(field + " must be an ISO-8601 instant");
        }
    }

    private void boundedCount(int value, String field) {
        require(value >= 0, field + " must be non-negative");
        require(value <= MAX_COUNT_VALUE, field + " exceeds maximum");
    }

    private void rejectForbidden(String value, String field) {
        String compact = compact(value);
        if (value.equals(PromotionReviewReadinessReportContract.REQUIRED_BANNER) || SAFE_LIMITATIONS.contains(value)) {
            return;
        }
        for (String safeField : List.of(
                "notPromotionApproval",
                "notThresholdRecommendation",
                "notProductionDecisioning",
                "notPaymentAuthorization",
                "notAutomaticDecisioning",
                "notAnalystRecommendation"
        )) {
            compact = compact.replace(compact(safeField), "");
        }
        for (String checkName : CHECK_NAMES) {
            compact = compact.replace(compact(checkName), "");
            compact = compact.replace(compact(checkName + "_FAILED"), "");
        }
        for (String term : FORBIDDEN_TERMS) {
            require(!compact.contains(term), field + " contains forbidden term");
        }
    }

    private String compact(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new PromotionReviewReadinessReportValidationException(message);
        }
    }
}
