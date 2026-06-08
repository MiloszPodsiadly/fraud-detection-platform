package com.frauddetection.alert.governance.shadowperformance;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
class ShadowPerformanceSummaryValidator {

    private static final int MAX_COUNT_VALUE = 500;
    private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern MACHINE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final Set<String> APPROVED_FOR = Set.of("COMPARE", "SHADOW");
    private static final Set<String> SAFE_LIMITATIONS = Set.of(
            "ANALYST_LABELS_ARE_EVALUATION_SIGNALS_NOT_GROUND_TRUTH",
            "BUCKET_ORDERED_METRICS_NOT_CALIBRATED_PROBABILITIES",
            "DIAGNOSTIC_ONLY",
            "NOT_EVALUATION_ELIGIBLE_EXCLUDED_FROM_QUALITY_METRICS",
            "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
            "NO_MODEL_PROMOTION_APPROVAL",
            "NO_PAYMENT_AUTHORIZATION",
            "NO_PRODUCTION_DECISIONING_APPROVAL",
            "NO_THRESHOLD_RECOMMENDATION",
            "OFFLINE_ONLY"
    );
    private static final Set<String> FORBIDDEN_TERMS = Set.of(
            "rawmodelcard",
            "rawevaluationreport",
            "rawdataset",
            "rawfdp102jsonl",
            "evaluationrecordid",
            "transactionreference",
            "customerid",
            "accountid",
            "cardid",
            "deviceid",
            "merchantid",
            "analystid",
            "submittedby",
            "correlationid",
            "requesthash",
            "idempotencykey",
            "rawpayload",
            "rawfeaturevector",
            "rawmlrequest",
            "rawmlresponse",
            "endpoint",
            "token",
            "secret",
            "stacktrace",
            "exceptionmessage",
            "groundtruth",
            "traininglabel",
            "modeltraininglabel",
            "finaldecision",
            "paymentauthorization",
            "productionapproved",
            "promotionapproved",
            "promotionready",
            "thresholdrecommendation",
            "recommendedthreshold",
            "championcandidate",
            "deployrecommendation"
    );

    void validate(ShadowPerformanceSummary summary) {
        if (summary == null) {
            throw new ShadowPerformanceSummaryValidationException("summary is missing");
        }
        require("SHADOW_PERFORMANCE_SUMMARY_V1".equals(summary.summaryType()), "summaryType is unsupported");
        require("1.0".equals(summary.summaryVersion()), "summaryVersion is unsupported");
        safeString(summary.generatedAt(), "generatedAt");
        validateModel(summary.model());
        validateGovernance(summary.governance());
        validateEvaluation(summary.evaluation());
        validatePopulation(summary.evaluationPopulation());
        validateMetrics(summary.metrics());
        validateDisagreement(summary.disagreementSummary());
        validateConsistency(summary.evaluationPopulation(), summary.metrics(), summary.disagreementSummary());
        validateMachineCodes(summary.warnings(), 10, "warnings");
        validateMachineCodes(summary.limitations(), 20, "limitations");
        require(summary.limitations() != null, "limitations is missing");
        require(Set.copyOf(summary.limitations()).containsAll(SAFE_LIMITATIONS), "limitations missing diagnostic non-goals");
        require(StaticShadowPerformanceSummaryProvider.REQUIRED_BANNER.equals(summary.banner()), "banner is unsupported");
    }

    private void validateModel(ShadowPerformanceSummary.ShadowPerformanceModel model) {
        require(model != null, "model is missing");
        safeIdentifier(model.modelName(), "modelName");
        safeIdentifier(model.modelVersion(), "modelVersion");
        machineCode(model.modelFamily(), "modelFamily");
        safeIdentifier(model.featureContractVersion(), "featureContractVersion");
    }

    private void validateGovernance(ShadowPerformanceSummary.ShadowPerformanceGovernance governance) {
        require(governance != null, "governance is missing");
        require("DIAGNOSTIC_ONLY".equals(governance.governanceStatus()), "governanceStatus is unsupported");
        require(governance.approvedFor() != null, "approvedFor is missing");
        require(APPROVED_FOR.equals(Set.copyOf(governance.approvedFor())), "approvedFor must be COMPARE and SHADOW only");
        require(governance.diagnosticOnly(), "diagnosticOnly must be true");
        require(governance.notProductionApproval(), "notProductionApproval must be true");
        require(governance.notPromotionApproval(), "notPromotionApproval must be true");
        require(governance.notThresholdRecommendation(), "notThresholdRecommendation must be true");
        require(governance.notPaymentAuthorization(), "notPaymentAuthorization must be true");
        require(governance.notAutomaticDecisioning(), "notAutomaticDecisioning must be true");
    }

    private void validateEvaluation(ShadowPerformanceSummary.ShadowPerformanceEvaluation evaluation) {
        require(evaluation != null, "evaluation is missing");
        require("PYTHON_ML_EVALUATION_FOUNDATION".equals(evaluation.evaluationReportType()), "evaluationReportType is unsupported");
        require("FDP-103".equals(evaluation.evaluationReportVersion()), "evaluationReportVersion is unsupported");
        require("bucket_ordered_offline_diagnostic".equals(evaluation.metricBasis()), "metricBasis is unsupported");
        require("FEEDBACK_SUBMITTED_AT".equals(evaluation.datasetTimeBasis()), "datasetTimeBasis is unsupported");
        require(
                "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC".equals(evaluation.datasetDeduplicationPolicy()),
                "datasetDeduplicationPolicy is unsupported"
        );
    }

    private void validatePopulation(ShadowPerformanceSummary.ShadowPerformancePopulation population) {
        require(population != null, "evaluationPopulation is missing");
        boundedCount(population.datasetRecordsRead(), "datasetRecordsRead");
        boundedCount(population.recordsAcceptedForEvaluation(), "recordsAcceptedForEvaluation");
        boundedCount(population.recordsExcludedNotEvaluationEligible(), "recordsExcludedNotEvaluationEligible");
        require(population.recordsAcceptedForEvaluation() <= population.datasetRecordsRead(), "accepted records exceed datasetRecordsRead");
        require(population.recordsExcludedNotEvaluationEligible() <= population.datasetRecordsRead(), "excluded records exceed datasetRecordsRead");
        require(
                population.recordsAcceptedForEvaluation() + population.recordsExcludedNotEvaluationEligible()
                        <= population.datasetRecordsRead(),
                "population total exceeds datasetRecordsRead"
        );
    }

    private void validateMetrics(ShadowPerformanceSummary.ShadowPerformanceMetrics metrics) {
        require(metrics != null, "metrics is missing");
        rate(metrics.precisionAtBudget(), "precisionAtBudget");
        rate(metrics.recallAtTopK(), "recallAtTopK");
        rate(metrics.falsePositiveRate(), "falsePositiveRate");
        boundedCount(metrics.mlCaughtRulesMissedCount(), "mlCaughtRulesMissedCount");
        boundedCount(metrics.rulesCaughtMlMissedCount(), "rulesCaughtMlMissedCount");
        boundedCount(metrics.missingMlCount(), "missingMlCount");
        boundedCount(metrics.missingRulesCount(), "missingRulesCount");
        boundedCount(metrics.missingProjectionCount(), "missingProjectionCount");
        boundedCount(metrics.notEvaluationEligibleCount(), "notEvaluationEligibleCount");
    }

    private void validateDisagreement(ShadowPerformanceSummary.ShadowPerformanceDisagreement disagreement) {
        require(disagreement != null, "disagreementSummary is missing");
        boundedCount(disagreement.rulesHighMlHigh(), "rulesHighMlHigh");
        boundedCount(disagreement.rulesHighMlLowOrMedium(), "rulesHighMlLowOrMedium");
        boundedCount(disagreement.rulesLowOrMediumMlHigh(), "rulesLowOrMediumMlHigh");
        boundedCount(disagreement.rulesLowOrMediumMlLowOrMedium(), "rulesLowOrMediumMlLowOrMedium");
        boundedCount(disagreement.rulesMissingMlPresent(), "rulesMissingMlPresent");
        boundedCount(disagreement.mlMissingRulesPresent(), "mlMissingRulesPresent");
        boundedCount(disagreement.bothMissing(), "bothMissing");
        boundedCount(disagreement.notEvaluationEligibleExcluded(), "notEvaluationEligibleExcluded");
    }

    private void validateConsistency(
            ShadowPerformanceSummary.ShadowPerformancePopulation population,
            ShadowPerformanceSummary.ShadowPerformanceMetrics metrics,
            ShadowPerformanceSummary.ShadowPerformanceDisagreement disagreement
    ) {
        int datasetRecords = population.datasetRecordsRead();
        require(metrics.notEvaluationEligibleCount() == population.recordsExcludedNotEvaluationEligible(),
                "notEvaluationEligibleCount must match recordsExcludedNotEvaluationEligible");
        List<Integer> metricCounts = List.of(
                metrics.mlCaughtRulesMissedCount(),
                metrics.rulesCaughtMlMissedCount(),
                metrics.missingMlCount(),
                metrics.missingRulesCount(),
                metrics.missingProjectionCount(),
                metrics.notEvaluationEligibleCount()
        );
        require(metricCounts.stream().allMatch(count -> count <= datasetRecords), "metric count exceeds datasetRecordsRead");
        int disagreementTotal = disagreement.rulesHighMlHigh()
                + disagreement.rulesHighMlLowOrMedium()
                + disagreement.rulesLowOrMediumMlHigh()
                + disagreement.rulesLowOrMediumMlLowOrMedium()
                + disagreement.rulesMissingMlPresent()
                + disagreement.mlMissingRulesPresent()
                + disagreement.bothMissing()
                + disagreement.notEvaluationEligibleExcluded();
        require(disagreementTotal <= datasetRecords, "disagreementSummary total exceeds datasetRecordsRead");
    }

    private void validateMachineCodes(List<String> values, int maxItems, String field) {
        require(values != null, field + " is missing");
        require(values.size() <= maxItems, field + " has too many items");
        for (String value : values) {
            machineCode(value, field);
        }
    }

    private void safeIdentifier(String value, String field) {
        safeString(value, field);
        require(SAFE_IDENTIFIER_PATTERN.matcher(value).matches(), field + " must be a safe identifier");
        String compact = compact(value);
        require(!value.contains(".."), field + " must not contain path traversal");
        require(!value.contains("/") && !value.contains("\\") && !value.contains(":") && !value.contains("@"),
                field + " must not be an artifact location");
        require(!compact.contains("registry") && !compact.contains("bucket") && !compact.contains("endpoint")
                        && !compact.contains("token") && !compact.contains("secret") && !compact.contains("artifact"),
                field + " must not contain operational location details");
    }

    private void machineCode(String value, String field) {
        safeString(value, field);
        require(MACHINE_CODE_PATTERN.matcher(value).matches(), field + " must be a machine-code string");
        rejectForbidden(value, field);
    }

    private void safeString(String value, String field) {
        require(value != null && !value.isBlank(), field + " is required");
        require(value.length() <= 256, field + " is too long");
        rejectForbidden(value, field);
    }

    private void boundedCount(int value, String field) {
        require(value >= 0, field + " must be non-negative");
        require(value <= MAX_COUNT_VALUE, field + " exceeds maximum");
    }

    private void rate(double value, String field) {
        require(!Double.isNaN(value) && value >= 0.0d && value <= 1.0d, field + " must be in range 0.0..1.0");
    }

    private void rejectForbidden(String value, String field) {
        String compact = compact(value);
        if (value.equals(StaticShadowPerformanceSummaryProvider.REQUIRED_BANNER) || SAFE_LIMITATIONS.contains(value)) {
            return;
        }
        require(!compact.contains("eval") && !compact.contains("txnref"), field + " contains forbidden reference");
        for (String term : FORBIDDEN_TERMS) {
            require(!compact.contains(term), field + " contains forbidden term");
        }
    }

    private String compact(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ShadowPerformanceSummaryValidationException(message);
        }
    }
}
