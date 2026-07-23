package com.frauddetection.alert.governance.shadowperformance;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
class ShadowPerformanceSummaryValidator {

    private static final int MAX_COUNT_VALUE = 1_000;
    private static final Pattern MACHINE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> SAFE_LIMITATIONS = Set.of(
            "ANALYST_FEEDBACK_LABELS_ARE_NOT_LEGAL_GROUND_TRUTH",
            "OFFLINE_DIAGNOSTIC_METRICS_ARE_NOT_PRODUCTION_APPROVAL",
            "METRICS_ARE_PLATFORM_RECOMMENDATION_DIAGNOSTICS",
            "SMALL_SAMPLE_SIZE_MAY_BE_INCONCLUSIVE",
            "PSEUDONYMOUS_REFERENCES_ARE_NOT_ANONYMIZATION",
            "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_APPROVE_PROMOTION",
            "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_AUTHORIZE_AUTOMATIC_DECLINE",
            "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_CHANGE_SCORING_THRESHOLDS"
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
            "productionapproved",
            "promotionapproved",
            "promotionready",
            "thresholdrecommendation",
            "recommendedthreshold",
            "paymentauthorization",
            "championcandidate",
            "deployrecommendation",
            "precisionatbudget",
            "recallattopk",
            "modelfamily"
    );

    void validate(ShadowPerformanceSummary summary) {
        if (summary == null) {
            throw new ShadowPerformanceSummaryValidationException("summary is missing");
        }
        require("SHADOW_PERFORMANCE_SUMMARY_V2".equals(summary.reportType()), "reportType is unsupported");
        require("shadow-performance-summary-v2".equals(summary.summaryVersion()), "summaryVersion is unsupported");
        instant(summary.generatedAt(), "generatedAt");
        validateSubject(summary.evaluationSubject());
        require("ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK".equals(summary.metricBasis()), "metricBasis is unsupported");
        validateGovernance(summary.governance());
        validateEvaluation(summary.evaluation());
        validatePopulation(summary.evaluationPopulation());
        validateMetrics(summary.metrics());
        validateMachineCodes(summary.warnings(), 20, "warnings");
        validateMachineCodes(summary.limitations(), 30, "limitations");
        require(summary.limitations() != null, "limitations is missing");
        require(Set.copyOf(summary.limitations()).containsAll(SAFE_LIMITATIONS), "limitations missing diagnostic non-goals");
        require(ShadowPerformanceSummaryContract.REQUIRED_BANNER.equals(summary.banner()), "banner is unsupported");
    }

    private void validateSubject(ShadowPerformanceSummary.EvaluationSubject subject) {
        require(subject != null, "evaluationSubject is missing");
        require("PLATFORM_RECOMMENDATION".equals(subject.subjectType()), "subjectType is unsupported");
        require("ENGINE_INTELLIGENCE_PROJECTION".equals(subject.sourceComponent()), "sourceComponent is unsupported");
        require("ENGINE_INTELLIGENCE_PROJECTION_V1".equals(subject.sourceVersion()), "sourceVersion is unsupported");
        require("NOT_APPLICABLE".equals(subject.featureContractVersion()), "featureContractVersion is unsupported");
        require("NOT_AVAILABLE".equals(subject.modelIdentity()), "modelIdentity is unsupported");
        require("NOT_AVAILABLE".equals(subject.modelArtifactSha256()), "modelArtifactSha256 is unsupported");
        require(
                "NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE".equals(subject.identityCompleteness()),
                "identityCompleteness is unsupported"
        );
    }

    private void validateGovernance(ShadowPerformanceSummary.ShadowPerformanceGovernance governance) {
        require(governance != null, "governance is missing");
        require("DIAGNOSTIC_ONLY".equals(governance.governanceStatus()), "governanceStatus is unsupported");
        require(governance.diagnosticOnly(), "diagnosticOnly must be true");
        require(governance.notProductionApproval(), "notProductionApproval must be true");
        require(governance.notPromotionApproval(), "notPromotionApproval must be true");
        require(governance.notThresholdRecommendation(), "notThresholdRecommendation must be true");
        require(governance.notPaymentAuthorization(), "notPaymentAuthorization must be true");
        require(governance.notAutomaticDecisioning(), "notAutomaticDecisioning must be true");
    }

    private void validateEvaluation(ShadowPerformanceSummary.ShadowPerformanceEvaluation evaluation) {
        require(evaluation != null, "evaluation is missing");
        require(
                "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1".equals(evaluation.evaluationCardType()),
                "evaluationCardType is unsupported"
        );
        require(
                "platform-recommendation-evaluation-card-v1".equals(evaluation.evaluationCardVersion()),
                "evaluationCardVersion is unsupported"
        );
        require("OFFLINE_DIAGNOSTIC".equals(evaluation.evaluationPurpose()), "evaluationPurpose is unsupported");
        require(
                "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1".equals(evaluation.evaluationReportType()),
                "evaluationReportType is unsupported"
        );
        require("FDP-124".equals(evaluation.evaluationReportVersion()), "evaluationReportVersion is unsupported");
        safeString(evaluation.evaluationArtifactSetVersion(), "evaluationArtifactSetVersion");
        safeString(evaluation.datasetVersion(), "datasetVersion");
        machineCode(evaluation.datasetTimeBasis(), "datasetTimeBasis");
        sha256(evaluation.sourceManifestSha256(), "sourceManifestSha256");
    }

    private void validatePopulation(ShadowPerformanceSummary.ShadowPerformancePopulation population) {
        require(population != null, "evaluationPopulation is missing");
        boundedCount(population.recordsEvaluated(), "recordsEvaluated");
        boundedCount(population.positiveClassCount(), "positiveClassCount");
        boundedCount(population.negativeClassCount(), "negativeClassCount");
        require(
                population.positiveClassCount() + population.negativeClassCount() == population.recordsEvaluated(),
                "positiveClassCount plus negativeClassCount must equal recordsEvaluated"
        );
    }

    private void validateMetrics(ShadowPerformanceSummary.ShadowPerformanceMetrics metrics) {
        require(metrics != null, "metrics is missing");
        metric(metrics.alertRecommendedPrecision(), "alertRecommendedPrecision");
        metric(metrics.alertRecommendedRecall(), "alertRecommendedRecall");
        metric(metrics.falsePositiveRate(), "falsePositiveRate");
        metric(metrics.falseNegativeRate(), "falseNegativeRate");
    }

    private void metric(ShadowPerformanceSummary.MetricValue metric, String field) {
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
        for (String value : values) {
            machineCode(value, field);
        }
    }

    private void safeString(String value, String field) {
        require(value != null && !value.isBlank(), field + " is required");
        require(value.length() <= 256, field + " is too long");
        rejectForbidden(value, field);
    }

    private void machineCode(String value, String field) {
        safeString(value, field);
        require(MACHINE_CODE_PATTERN.matcher(value).matches(), field + " must be a machine-code string");
    }

    private void sha256(String value, String field) {
        safeString(value, field);
        require(SHA256_PATTERN.matcher(value).matches(), field + " must be sha256 hex");
    }

    private void instant(String value, String field) {
        safeString(value, field);
        try {
            Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ShadowPerformanceSummaryValidationException(field + " must be an ISO-8601 instant");
        }
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
        if (value.equals(ShadowPerformanceSummaryContract.REQUIRED_BANNER) || SAFE_LIMITATIONS.contains(value)) {
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
