package com.frauddetection.alert.governance.promotionreviewreadiness;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Component
class PromotionReviewReadinessReportValidator {

    private static final Pattern MACHINE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern RFC3339_TIMESTAMP_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"
    );
    private static final int MAX_DIAGNOSTIC_RECORDS = 1_000;
    private static final Set<String> READINESS_STATUSES = Set.of("INSUFFICIENT_DATA", "INCONCLUSIVE", "NOT_REVIEWABLE", "REVIEWABLE");
    private static final Set<String> CHECK_STATUSES = Set.of("PASS", "FAIL", "INCONCLUSIVE");
    private static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH");
    private static final Set<String> CHECK_NAMES = Set.of(
            "CURRENT_SUMMARY_PRESENT",
            "CURRENT_SUMMARY_VERSION_SUPPORTED",
            "EVALUATION_CARD_PRESENT",
            "EVALUATION_CARD_VERSION_SUPPORTED",
            "GOVERNANCE_STATUS_DIAGNOSTIC_ONLY",
            "NOT_PRODUCTION_APPROVAL_TRUE",
            "NOT_PROMOTION_APPROVAL_TRUE",
            "NOT_THRESHOLD_RECOMMENDATION_TRUE",
            "NOT_PAYMENT_AUTHORIZATION_TRUE",
            "NOT_AUTOMATIC_DECISIONING_TRUE",
            "EVALUATION_REPORT_TYPE_SUPPORTED",
            "METRIC_BASIS_SUPPORTED",
            "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS",
            "ALERT_RECOMMENDED_PRECISION_AVAILABLE",
            "ALERT_RECOMMENDED_RECALL_AVAILABLE",
            "FALSE_POSITIVE_RATE_AVAILABLE",
            "FALSE_NEGATIVE_RATE_AVAILABLE"
    );
    private static final Map<String, String> CHECK_SEVERITIES = Map.ofEntries(
            Map.entry("CURRENT_SUMMARY_PRESENT", "INFO"),
            Map.entry("CURRENT_SUMMARY_VERSION_SUPPORTED", "INFO"),
            Map.entry("EVALUATION_CARD_PRESENT", "INFO"),
            Map.entry("EVALUATION_CARD_VERSION_SUPPORTED", "INFO"),
            Map.entry("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY", "INFO"),
            Map.entry("NOT_PRODUCTION_APPROVAL_TRUE", "INFO"),
            Map.entry("NOT_PROMOTION_APPROVAL_TRUE", "INFO"),
            Map.entry("NOT_THRESHOLD_RECOMMENDATION_TRUE", "INFO"),
            Map.entry("NOT_PAYMENT_AUTHORIZATION_TRUE", "INFO"),
            Map.entry("NOT_AUTOMATIC_DECISIONING_TRUE", "INFO"),
            Map.entry("EVALUATION_REPORT_TYPE_SUPPORTED", "INFO"),
            Map.entry("METRIC_BASIS_SUPPORTED", "INFO"),
            Map.entry("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", "HIGH"),
            Map.entry("ALERT_RECOMMENDED_PRECISION_AVAILABLE", "MEDIUM"),
            Map.entry("ALERT_RECOMMENDED_RECALL_AVAILABLE", "MEDIUM"),
            Map.entry("FALSE_POSITIVE_RATE_AVAILABLE", "MEDIUM"),
            Map.entry("FALSE_NEGATIVE_RATE_AVAILABLE", "MEDIUM")
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
        Instant reportGeneratedAt = instant(report.generatedAt(), "generatedAt");
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
        validateInputs(report.inputs(), reportGeneratedAt);
        validateCheckInputs(report.checkInputs());
        validateInputsMatchCheckInputs(report.inputs(), report.checkInputs());
        List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks = validateChecks(report.checks());
        List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> expectedChecks = checksFromInputs(report.checkInputs());
        require(checks.equals(expectedChecks), "checks must match checkInputs");
        validateMachineCodes(report.reasonCodes(), 20, "reasonCodes");
        validateMachineCodes(report.warnings(), 20, "warnings");
        validateMachineCodes(report.limitations(), 20, "limitations");
        require(report.readinessStatus().equals(derivedReadinessStatus(checks)),
                "readinessStatus must match required checks");
        require(report.reasonCodes().equals(derivedReasonCodes(checks)),
                "reasonCodes must match required checks");
        require(Set.copyOf(report.limitations()).containsAll(SAFE_LIMITATIONS), "limitations missing diagnostic non-goals");
        require(PromotionReviewReadinessReportContract.REQUIRED_BANNER.equals(report.banner()), "banner is unsupported");
    }

    private void validateInputs(PromotionReviewReadinessReport.PromotionReviewReadinessInputs inputs, Instant reportGeneratedAt) {
        require(inputs != null, "inputs is missing");
        Instant summaryGeneratedAt = validateSummaryInput(inputs.shadowPerformanceSummary());
        require(!reportGeneratedAt.isBefore(summaryGeneratedAt), "generatedAt must be >= shadowPerformanceSummary.generatedAt");
        boundedCount(inputs.minimumDiagnosticEvidenceRecords(), "minimumDiagnosticEvidenceRecords");
        require(inputs.minimumDiagnosticEvidenceRecords() > 0, "minimumDiagnosticEvidenceRecords must be positive");
        boundedCount(inputs.recordsEvaluated(), "recordsEvaluated");
    }

    private Instant validateSummaryInput(PromotionReviewReadinessReport.ShadowPerformanceSummaryInput input) {
        require(input != null, "shadowPerformanceSummary input is missing");
        require(input.present(), "shadowPerformanceSummary.present must be true");
        require("SHADOW_PERFORMANCE_SUMMARY_V2".equals(input.reportType()), "reportType is unsupported");
        require("shadow-performance-summary-v2".equals(input.summaryVersion()), "summaryVersion is unsupported");
        return instant(input.generatedAt(), "shadowPerformanceSummary.generatedAt");
    }

    private void validateCheckInputs(PromotionReviewReadinessReport.PromotionReviewReadinessCheckInputs inputs) {
        require(inputs != null, "checkInputs is missing");
        sha256(inputs.sourceShadowSummaryManifestSha256(), "sourceShadowSummaryManifestSha256");
        PromotionReviewReadinessReport.ShadowPerformanceSummaryCheckInput summary = inputs.shadowPerformanceSummary();
        require(summary != null, "checkInputs.shadowPerformanceSummary is missing");
        require(summary.present(), "checkInputs.shadowPerformanceSummary.present must be true");
        require("SHADOW_PERFORMANCE_SUMMARY_V2".equals(summary.reportType()), "checkInputs.shadowPerformanceSummary.reportType is unsupported");
        require("shadow-performance-summary-v2".equals(summary.summaryVersion()), "checkInputs.shadowPerformanceSummary.summaryVersion is unsupported");
        instant(summary.generatedAt(), "checkInputs.shadowPerformanceSummary.generatedAt");
        sha256(summary.sourceEvaluationCardManifestSha256(), "sourceEvaluationCardManifestSha256");
        PromotionReviewReadinessReport.PromotionReadinessGovernanceCheckInput governance = inputs.governance();
        require(governance != null, "checkInputs.governance is missing");
        require(PromotionReviewReadinessReportContract.GOVERNANCE_STATUS.equals(governance.governanceStatus()),
                "checkInputs.governance.governanceStatus is unsupported");
        require(governance.diagnosticOnly(), "checkInputs.governance.diagnosticOnly must be true");
        require(governance.notProductionApproval(), "checkInputs.governance.notProductionApproval must be true");
        require(governance.notPromotionApproval(), "checkInputs.governance.notPromotionApproval must be true");
        require(governance.notThresholdRecommendation(), "checkInputs.governance.notThresholdRecommendation must be true");
        require(governance.notPaymentAuthorization(), "checkInputs.governance.notPaymentAuthorization must be true");
        require(governance.notAutomaticDecisioning(), "checkInputs.governance.notAutomaticDecisioning must be true");
        PromotionReviewReadinessReport.PromotionReadinessEvaluationCheckInput evaluation = inputs.evaluation();
        require(evaluation != null, "checkInputs.evaluation is missing");
        safeString(evaluation.evaluationCardType(), "checkInputs.evaluation.evaluationCardType");
        safeString(evaluation.evaluationCardVersion(), "checkInputs.evaluation.evaluationCardVersion");
        safeString(evaluation.evaluationReportType(), "checkInputs.evaluation.evaluationReportType");
        require("ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK".equals(inputs.metricBasis()), "checkInputs.metricBasis is unsupported");
        boundedCount(inputs.minimumDiagnosticEvidenceRecords(), "checkInputs.minimumDiagnosticEvidenceRecords");
        require(inputs.minimumDiagnosticEvidenceRecords() > 0, "checkInputs.minimumDiagnosticEvidenceRecords must be positive");
        boundedCount(inputs.recordsEvaluated(), "checkInputs.recordsEvaluated");
        PromotionReviewReadinessReport.PromotionReadinessMetricsCheckInput metrics = inputs.metrics();
        require(metrics != null, "checkInputs.metrics is missing");
        metric(metrics.alertRecommendedPrecision(), "checkInputs.metrics.alertRecommendedPrecision");
        metric(metrics.alertRecommendedRecall(), "checkInputs.metrics.alertRecommendedRecall");
        metric(metrics.falsePositiveRate(), "checkInputs.metrics.falsePositiveRate");
        metric(metrics.falseNegativeRate(), "checkInputs.metrics.falseNegativeRate");
    }

    private void validateInputsMatchCheckInputs(
            PromotionReviewReadinessReport.PromotionReviewReadinessInputs inputs,
            PromotionReviewReadinessReport.PromotionReviewReadinessCheckInputs checkInputs
    ) {
        PromotionReviewReadinessReport.ShadowPerformanceSummaryInput summary = inputs.shadowPerformanceSummary();
        PromotionReviewReadinessReport.ShadowPerformanceSummaryCheckInput checkSummary = checkInputs.shadowPerformanceSummary();
        require(summary.present() == checkSummary.present(), "inputs must match checkInputs");
        require(summary.reportType().equals(checkSummary.reportType()), "inputs must match checkInputs");
        require(summary.summaryVersion().equals(checkSummary.summaryVersion()), "inputs must match checkInputs");
        require(summary.generatedAt().equals(checkSummary.generatedAt()), "inputs must match checkInputs");
        require(inputs.minimumDiagnosticEvidenceRecords() == checkInputs.minimumDiagnosticEvidenceRecords(), "inputs must match checkInputs");
        require(inputs.recordsEvaluated() == checkInputs.recordsEvaluated(), "inputs must match checkInputs");
    }

    private List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> validateChecks(
            List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks
    ) {
        require(checks != null && !checks.isEmpty(), "checks are missing");
        require(checks.size() == CHECK_NAMES.size(), "checks must contain exactly the required checks");
        Set<String> names = new TreeSet<>();
        List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> normalized = new ArrayList<>();
        for (PromotionReviewReadinessReport.PromotionReviewReadinessCheck check : checks) {
            require(check != null, "check is missing");
            machineCode(check.name(), "check.name");
            require(CHECK_NAMES.contains(check.name()), "check.name is unsupported");
            require(names.add(check.name()), "check.name is duplicated");
            machineCode(check.status(), "check.status");
            require(CHECK_STATUSES.contains(check.status()), "check.status is unsupported");
            require(!"INCONCLUSIVE".equals(check.status()) || check.name().endsWith("_AVAILABLE"),
                    "check.status is unsupported for check");
            machineCode(check.severity(), "check.severity");
            require(SEVERITIES.contains(check.severity()), "check.severity is unsupported");
            require(CHECK_SEVERITIES.get(check.name()).equals(check.severity()),
                    "check.severity does not match required check");
            normalized.add(check);
        }
        require(names.equals(CHECK_NAMES), "checks must contain exactly the required checks");
        return List.copyOf(normalized);
    }

    private List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checksFromInputs(
            PromotionReviewReadinessReport.PromotionReviewReadinessCheckInputs inputs
    ) {
        PromotionReviewReadinessReport.PromotionReadinessGovernanceCheckInput governance = inputs.governance();
        PromotionReviewReadinessReport.PromotionReadinessEvaluationCheckInput evaluation = inputs.evaluation();
        PromotionReviewReadinessReport.PromotionReadinessMetricsCheckInput metrics = inputs.metrics();
        return List.of(
                check("CURRENT_SUMMARY_PRESENT", passFail(inputs.shadowPerformanceSummary().present())),
                check("CURRENT_SUMMARY_VERSION_SUPPORTED", passFail("shadow-performance-summary-v2".equals(inputs.shadowPerformanceSummary().summaryVersion()))),
                check("EVALUATION_CARD_PRESENT", passFail("PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1".equals(evaluation.evaluationCardType()))),
                check("EVALUATION_CARD_VERSION_SUPPORTED", passFail("platform-recommendation-evaluation-card-v1".equals(evaluation.evaluationCardVersion()))),
                check("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY", passFail(PromotionReviewReadinessReportContract.GOVERNANCE_STATUS.equals(governance.governanceStatus()))),
                check("NOT_PRODUCTION_APPROVAL_TRUE", passFail(governance.notProductionApproval())),
                check("NOT_PROMOTION_APPROVAL_TRUE", passFail(governance.notPromotionApproval())),
                check("NOT_THRESHOLD_RECOMMENDATION_TRUE", passFail(governance.notThresholdRecommendation())),
                check("NOT_PAYMENT_AUTHORIZATION_TRUE", passFail(governance.notPaymentAuthorization())),
                check("NOT_AUTOMATIC_DECISIONING_TRUE", passFail(governance.notAutomaticDecisioning())),
                check("EVALUATION_REPORT_TYPE_SUPPORTED", passFail("FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1".equals(evaluation.evaluationReportType()))),
                check("METRIC_BASIS_SUPPORTED", passFail("ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK".equals(inputs.metricBasis()))),
                check("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", passFail(inputs.recordsEvaluated() >= inputs.minimumDiagnosticEvidenceRecords()), "HIGH"),
                metricCheck("ALERT_RECOMMENDED_PRECISION_AVAILABLE", metrics.alertRecommendedPrecision()),
                metricCheck("ALERT_RECOMMENDED_RECALL_AVAILABLE", metrics.alertRecommendedRecall()),
                metricCheck("FALSE_POSITIVE_RATE_AVAILABLE", metrics.falsePositiveRate()),
                metricCheck("FALSE_NEGATIVE_RATE_AVAILABLE", metrics.falseNegativeRate())
        );
    }

    private PromotionReviewReadinessReport.PromotionReviewReadinessCheck check(String name, String status) {
        return check(name, status, "INFO");
    }

    private PromotionReviewReadinessReport.PromotionReviewReadinessCheck check(String name, String status, String severity) {
        return new PromotionReviewReadinessReport.PromotionReviewReadinessCheck(name, status, severity);
    }

    private PromotionReviewReadinessReport.PromotionReviewReadinessCheck metricCheck(
            String name,
            PromotionReviewReadinessReport.PromotionReadinessMetricCheckInput metric
    ) {
        return check(name, Boolean.TRUE.equals(metric.available()) ? "PASS" : "INCONCLUSIVE", "MEDIUM");
    }

    private String passFail(boolean condition) {
        return condition ? "PASS" : "FAIL";
    }

    private void metric(PromotionReviewReadinessReport.PromotionReadinessMetricCheckInput metric, String field) {
        require(metric != null, field + " is missing");
        require(metric.available() != null, field + ".available is missing");
        if (Boolean.TRUE.equals(metric.available())) {
            require(metric.value() != null, field + ".value is required when available");
            rate(metric.value(), field + ".value");
            require(metric.reason() == null, field + ".reason must be null when available");
        } else {
            require(metric.value() == null, field + ".value must be null when unavailable");
            machineCode(metric.reason(), field + ".reason");
        }
    }

    private void validateMachineCodes(List<String> values, int maxItems, String field) {
        require(values != null, field + " is missing");
        require(values.size() <= maxItems, field + " has too many items");
        require(Set.copyOf(values).size() == values.size(), field + " contains duplicate values");
        for (String value : values) {
            machineCode(value, field);
        }
    }

    private void machineCode(String value, String field) {
        safeString(value, field);
        require(MACHINE_CODE_PATTERN.matcher(value).matches(), field + " must be a machine-code string");
    }

    private void safeString(String value, String field) {
        require(value != null && !value.isBlank(), field + " is required");
        require(value.length() <= 512, field + " is too long");
        rejectForbidden(value, field);
    }

    private void sha256(String value, String field) {
        safeString(value, field);
        require(SHA256_PATTERN.matcher(value).matches(), field + " must be sha256 hex");
    }

    private Instant instant(String value, String field) {
        safeString(value, field);
        require(RFC3339_TIMESTAMP_PATTERN.matcher(value).matches(), field + " must be an ISO-8601 instant");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new PromotionReviewReadinessReportValidationException(field + " must be an ISO-8601 instant");
        }
    }

    private void boundedCount(int value, String field) {
        require(value >= 0, field + " must be non-negative");
        require(value <= MAX_DIAGNOSTIC_RECORDS, field + " exceeds maximum");
    }

    private void rate(double value, String field) {
        require(!Double.isNaN(value) && value >= 0.0d && value <= 1.0d, field + " must be in range 0.0..1.0");
    }

    private String derivedReadinessStatus(List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks) {
        List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> failedChecks = checks.stream()
                .filter(check -> "FAIL".equals(check.status()))
                .toList();
        if (failedChecks.stream().anyMatch(check -> !"MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS".equals(check.name()))) {
            return "NOT_REVIEWABLE";
        }
        if (failedChecks.stream().anyMatch(check -> "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS".equals(check.name()))) {
            return "INSUFFICIENT_DATA";
        }
        boolean hasInconclusive = checks.stream().anyMatch(check -> "INCONCLUSIVE".equals(check.status()));
        return hasInconclusive ? "INCONCLUSIVE" : "REVIEWABLE";
    }

    private List<String> derivedReasonCodes(List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks) {
        List<String> reasonCodes = new ArrayList<>();
        for (PromotionReviewReadinessReport.PromotionReviewReadinessCheck check : checks) {
            if ("FAIL".equals(check.status())) {
                reasonCodes.add(check.name() + "_FAILED");
            } else if ("INCONCLUSIVE".equals(check.status())) {
                reasonCodes.add(check.name() + "_INCONCLUSIVE");
            }
        }
        return reasonCodes.stream().sorted().toList();
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
            compact = compact.replace(compact(checkName + "_INCONCLUSIVE"), "");
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
