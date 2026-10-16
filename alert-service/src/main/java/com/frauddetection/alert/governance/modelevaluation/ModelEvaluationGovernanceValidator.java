package com.frauddetection.alert.governance.modelevaluation;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Component
public class ModelEvaluationGovernanceValidator {

    private static final String EVALUATION_REPORT_TYPE = "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_RECORDS = 1_000;
    private static final Set<String> SUMMARY_FIELDS = Set.of(
            "reportType", "generatedAt", "sourceDataset", "metricBasis", "evaluationSubject",
            "evaluationWindow", "population", "classBalance", "lineagePolicy", "supportedMetrics",
            "limitations", "warnings"
    );
    private static final Set<String> SUBJECT_FIELDS = Set.of(
            "subjectType", "modelName", "modelVersion", "featureContractVersion",
            "modelArtifactSha256", "identityCompleteness"
    );
    private static final Set<String> SOURCE_FIELDS = Set.of(
            "datasetVersion", "sha256", "rawRowsRead", "recordsReturned", "excludedUnresolvedCount",
            "excludedGovernanceReviewCount", "skippedMissingRequiredFieldCount",
            "skippedInvalidSourceRecordCount", "truncated"
    );
    private static final Set<String> POPULATION_FIELDS = Set.of(
            "recordsConsidered", "recordsWithPredictionEvidence", "recordsEvaluated",
            "recordsExcludedIdentityMismatch", "recordsExcludedSourceIdentityMismatch",
            "recordsExcludedMissingPredictionEvidence", "recordsExcludedUnexpectedMissingPredictionEvidence",
            "recordsExcludedInvalidPredictionEvidence"
    );
    private static final Set<String> LIMITATIONS = Set.of(
            "ANALYST_FEEDBACK_LABELS_ARE_REVIEW_SIGNALS",
            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_APPROVE_PROMOTION",
            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_CHANGE_SCORING",
            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_AUTHORIZE_PAYMENTS",
            "ML_PREDICTION_METRICS_REQUIRE_DIRECT_ML_OUTPUT_SIGNALS"
    );
    public void validateEvaluationSummary(
            JsonNode summary,
            ExpectedSubject expectedSubject,
            String reportGeneratedAt,
            String containerGeneratedAt
    ) {
        require(summary != null && summary.isObject(), "evaluationSummary must be an object");
        exactFields(summary, SUMMARY_FIELDS, "evaluationSummary");
        require(EVALUATION_REPORT_TYPE.equals(text(summary, "reportType")),
                "evaluationSummary.reportType unsupported");
        require("BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_ARTIFACT".equals(text(summary, "metricBasis")),
                "evaluationSummary.metricBasis unsupported");
        String summaryGeneratedAt = text(summary, "generatedAt");
        timestamp(summaryGeneratedAt, "evaluationSummary.generatedAt");
        require(summaryGeneratedAt.equals(reportGeneratedAt),
                "evaluation report timestamp mismatch");
        require(!GovernanceTimestampContract.parse(containerGeneratedAt, "generatedAt")
                        .isBefore(GovernanceTimestampContract.parse(
                                summaryGeneratedAt, "evaluationSummary.generatedAt")),
                "model card predates evaluation");
        validateSummarySubject(summary.get("evaluationSubject"), expectedSubject);
        validateSourceDataset(summary.get("sourceDataset"));
        validateWindow(summary.get("evaluationWindow"));
        Map<String, Integer> population = validatePopulation(summary.get("population"));
        Map<String, Integer> balance = validateClassBalance(summary.get("classBalance"));
        require(population.get("recordsEvaluated")
                        == balance.get("positiveClassCount") + balance.get("negativeClassCount"),
                "class balance does not reconcile");
        validatePopulationReconciliation(
                population,
                integer(summary.get("sourceDataset"), "recordsReturned", MAX_RECORDS)
        );
        validateLineage(summary.get("lineagePolicy"));
        validateSupportedMetrics(summary.get("supportedMetrics"), population, balance);
        exactMachineCodes(summary.get("limitations"), LIMITATIONS, true, "limitations");
        validateExpectedWarnings(summary.get("warnings"), summary.get("sourceDataset"), population, balance);
    }

    private void validateSummarySubject(JsonNode subject, ExpectedSubject expected) {
        exactFields(subject, SUBJECT_FIELDS, "evaluationSubject");
        require(expected.subjectType().equals(text(subject, "subjectType")), "evaluation subject mismatch");
        require(expected.modelName().equals(text(subject, "modelName")), "evaluation subject mismatch");
        require(expected.modelVersion().equals(text(subject, "modelVersion")), "evaluation subject mismatch");
        require(expected.featureContractVersion().equals(text(subject, "featureContractVersion")),
                "evaluation subject mismatch");
        require(expected.modelArtifactSha256().equals(text(subject, "modelArtifactSha256")),
                "evaluation subject mismatch");
        require(expected.identityCompleteness().equals(text(subject, "identityCompleteness")),
                "evaluation subject mismatch");
    }

    private void validateSourceDataset(JsonNode source) {
        exactFields(source, SOURCE_FIELDS, "sourceDataset");
        require("feedback-dataset-v3".equals(text(source, "datasetVersion")), "datasetVersion unsupported");
        sha256(text(source, "sha256"), "sourceDataset.sha256");
        int rawRows = integer(source, "rawRowsRead", MAX_RECORDS + 1);
        int accounted = integer(source, "recordsReturned", MAX_RECORDS)
                + integer(source, "excludedUnresolvedCount", MAX_RECORDS)
                + integer(source, "excludedGovernanceReviewCount", MAX_RECORDS)
                + integer(source, "skippedMissingRequiredFieldCount", MAX_RECORDS)
                + integer(source, "skippedInvalidSourceRecordCount", MAX_RECORDS);
        JsonNode truncated = source.get("truncated");
        require(truncated != null && truncated.isBoolean(), "sourceDataset.truncated must be boolean");
        require(rawRows == accounted + (truncated.booleanValue() ? 1 : 0), "source dataset counts do not reconcile");
    }

    public void validateWindow(JsonNode window) {
        exactFields(window, Set.of("timeBasis", "fromInclusive", "toInclusive"), "evaluationWindow");
        require("FEEDBACK_CREATED_AT".equals(text(window, "timeBasis")), "evaluationWindow.timeBasis unsupported");
        optionalTimestamp(window.get("fromInclusive"), "evaluationWindow.fromInclusive");
        optionalTimestamp(window.get("toInclusive"), "evaluationWindow.toInclusive");
        JsonNode fromInclusive = window.get("fromInclusive");
        JsonNode toInclusive = window.get("toInclusive");
        if (!fromInclusive.isNull() && !toInclusive.isNull()) {
            require(!GovernanceTimestampContract.parse(
                            fromInclusive.textValue(), "evaluationWindow.fromInclusive")
                            .isAfter(GovernanceTimestampContract.parse(
                                    toInclusive.textValue(), "evaluationWindow.toInclusive")),
                    "evaluationWindow.fromInclusive must not be later than evaluationWindow.toInclusive");
        }
    }

    private Map<String, Integer> validatePopulation(JsonNode population) {
        exactFields(population, POPULATION_FIELDS, "population");
        return POPULATION_FIELDS.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                field -> field,
                field -> integer(population, field, MAX_RECORDS)
        ));
    }

    private Map<String, Integer> validateClassBalance(JsonNode balance) {
        exactFields(balance, Set.of("positiveClassCount", "negativeClassCount"), "classBalance");
        return Map.of(
                "positiveClassCount", integer(balance, "positiveClassCount", MAX_RECORDS),
                "negativeClassCount", integer(balance, "negativeClassCount", MAX_RECORDS)
        );
    }

    private void validatePopulationReconciliation(Map<String, Integer> population, int recordsReturned) {
        int excluded = population.get("recordsExcludedIdentityMismatch")
                + population.get("recordsExcludedSourceIdentityMismatch")
                + population.get("recordsExcludedMissingPredictionEvidence")
                + population.get("recordsExcludedUnexpectedMissingPredictionEvidence")
                + population.get("recordsExcludedInvalidPredictionEvidence");
        require(population.get("recordsConsidered") == population.get("recordsEvaluated") + excluded,
                "population counts do not reconcile");
        require(population.get("recordsWithPredictionEvidence")
                        == population.get("recordsEvaluated") + population.get("recordsExcludedIdentityMismatch"),
                "prediction evidence counts do not reconcile");
        require(population.get("recordsConsidered") == recordsReturned,
                "population does not match source dataset");
    }

    private void validateLineage(JsonNode lineage) {
        exactFields(lineage, Set.of("policy", "identityMismatchBehavior", "identityMismatchReason"),
                "lineagePolicy");
        require("REQUESTED_EXACT_ARTIFACT_IDENTITY".equals(text(lineage, "policy")),
                "lineagePolicy.policy unsupported");
        require("EXCLUDE".equals(text(lineage, "identityMismatchBehavior")),
                "lineagePolicy.identityMismatchBehavior unsupported");
        require("MODEL_IDENTITY_MISMATCH".equals(text(lineage, "identityMismatchReason")),
                "lineagePolicy.identityMismatchReason unsupported");
    }

    private void validateSupportedMetrics(
            JsonNode metrics,
            Map<String, Integer> population,
            Map<String, Integer> balance
    ) {
        exactFields(metrics, Set.of("classBalance", "mlPredictionMetrics", "rulesVsMlDisagreement"),
                "supportedMetrics");
        validateAvailability(
                metrics.get("classBalance"),
                population.get("recordsEvaluated") > 0,
                "INSUFFICIENT_MODEL_LINEAGE_RECORDS",
                "supportedMetrics.classBalance"
        );
        validatePredictionMetrics(metrics.get("mlPredictionMetrics"), population.get("recordsEvaluated"), balance);
        validateDisagreement(
                metrics.get("rulesVsMlDisagreement"),
                population.get("recordsEvaluated"),
                balance.get("positiveClassCount")
        );
    }

    private void validatePredictionMetrics(JsonNode metrics, int recordsEvaluated, Map<String, Integer> balance) {
        Set<String> fields = Set.of(
                "available", "reason", "classificationPolicy", "rankingPolicy", "recordsEvaluated",
                "truePositive", "falsePositive", "trueNegative", "falseNegative", "precision", "recall",
                "truePositiveRate", "falsePositiveRate", "falseNegativeRate", "precisionAtK", "recallAtK"
        );
        exactFields(metrics, fields, "mlPredictionMetrics");
        require(integer(metrics, "recordsEvaluated", MAX_RECORDS) == recordsEvaluated,
                "metric population mismatch");
        int tp = integer(metrics, "truePositive", MAX_RECORDS);
        int fp = integer(metrics, "falsePositive", MAX_RECORDS);
        int tn = integer(metrics, "trueNegative", MAX_RECORDS);
        int fn = integer(metrics, "falseNegative", MAX_RECORDS);
        require(tp + fn == balance.get("positiveClassCount"), "positive confusion counts do not reconcile");
        require(tn + fp == balance.get("negativeClassCount"), "negative confusion counts do not reconcile");
        require(tp + fp + tn + fn == recordsEvaluated, "confusion counts do not reconcile");
        require("RISK_HIGH_OR_CRITICAL_POSITIVE_V1".equals(text(metrics, "classificationPolicy")),
                "classificationPolicy unsupported");
        require("EXACT_ML_PREDICTION_SCORE_DESC_EVALUATION_RECORD_ID_ASC_V1"
                        .equals(text(metrics, "rankingPolicy")), "rankingPolicy unsupported");
        validateAvailabilityFields(metrics, recordsEvaluated > 0, "MODEL_PREDICTION_SIGNAL_UNAVAILABLE");
        validateExpectedMetric(metrics.get("precision"), tp, tp + fp,
                "NO_PREDICTED_POSITIVES", "mlPredictionMetrics.precision");
        validateExpectedMetric(metrics.get("recall"), tp, tp + fn,
                "NO_ACTUAL_POSITIVES", "mlPredictionMetrics.recall");
        validateExpectedMetric(metrics.get("truePositiveRate"), tp, tp + fn,
                "NO_ACTUAL_POSITIVES", "mlPredictionMetrics.truePositiveRate");
        validateExpectedMetric(metrics.get("falsePositiveRate"), fp, fp + tn,
                "NO_ACTUAL_NEGATIVES", "mlPredictionMetrics.falsePositiveRate");
        validateExpectedMetric(metrics.get("falseNegativeRate"), fn, fn + tp,
                "NO_ACTUAL_POSITIVES", "mlPredictionMetrics.falseNegativeRate");
        validateRankedMetrics(
                metrics.get("precisionAtK"),
                metrics.get("recallAtK"),
                recordsEvaluated,
                balance.get("positiveClassCount")
        );
    }

    private void validateRankedMetrics(
            JsonNode precisionAtK,
            JsonNode recallAtK,
            int recordsEvaluated,
            int totalPositive
    ) {
        require(precisionAtK != null && precisionAtK.isObject() && !precisionAtK.isEmpty(),
                "precisionAtK must be a non-empty object");
        require(recallAtK != null && recallAtK.isObject(), "recallAtK must be an object");
        require(precisionAtK.size() <= 32, "precisionAtK contains too many K values");
        require(precisionAtK.size() == recallAtK.size(), "ranking metric key sets differ");
        Map<Integer, RankedPrefix> prefixes = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : precisionAtK.properties()) {
            require(recallAtK.has(entry.getKey()), "ranking metric key sets differ");
            JsonNode precisionRow = entry.getValue();
            JsonNode recallRow = recallAtK.get(entry.getKey());
            exactFields(precisionRow, Set.of("requestedK", "actualK", "value"), "precisionAtK row");
            exactFields(recallRow, Set.of("requestedK", "actualK", "value"), "recallAtK row");
            int requested = integer(precisionRow, "requestedK", MAX_RECORDS);
            require(requested > 0 && entry.getKey().equals(Integer.toString(requested)),
                    "ranking requestedK is invalid");
            require(integer(recallRow, "requestedK", MAX_RECORDS) == requested,
                    "ranking requestedK is inconsistent");
            int actual = integer(precisionRow, "actualK", MAX_RECORDS);
            require(actual == Math.min(requested, recordsEvaluated)
                            && integer(recallRow, "actualK", MAX_RECORDS) == actual,
                    "ranking actualK is inconsistent");
            int matchedPositiveCount = -1;
            for (int positiveAtK = 0; positiveAtK <= Math.min(actual, totalPositive); positiveAtK++) {
                if (matchesExpectedMetric(precisionRow.get("value"), positiveAtK, actual, "NO_SCORED_RECORDS")
                        && matchesExpectedMetric(
                        recallRow.get("value"),
                        recordsEvaluated > 0 ? positiveAtK : 0,
                        recordsEvaluated > 0 ? totalPositive : 0,
                        recordsEvaluated > 0 ? "NO_POSITIVE_RECORDS" : "NO_SCORED_RECORDS"
                )) {
                    require(matchedPositiveCount < 0, "ranking metric values are ambiguous");
                    matchedPositiveCount = positiveAtK;
                }
            }
            require(matchedPositiveCount >= 0, "ranking metric values are inconsistent");
            prefixes.put(requested, new RankedPrefix(actual, matchedPositiveCount));
        }
        int previousActual = 0;
        int previousPositive = 0;
        for (RankedPrefix prefix : prefixes.values()) {
            require(prefix.actualK() >= previousActual
                            && prefix.positiveCount() >= previousPositive
                            && prefix.positiveCount() - previousPositive <= prefix.actualK() - previousActual,
                    "ranking prefixes are inconsistent");
            previousActual = prefix.actualK();
            previousPositive = prefix.positiveCount();
        }
        require(previousActual != recordsEvaluated || previousPositive == totalPositive,
                "complete ranking must include all positive records");
    }

    private void validateDisagreement(JsonNode value, int recordsEvaluated, int positiveClassCount) {
        exactFields(value, Set.of(
                "available", "reason", "classificationPolicy", "recordsWithExactMlEvidence", "recordsCompared",
                "recordsExcludedRulesEvidenceUnavailable", "categoryCounts", "disagreementRate",
                "analystLabeledFraudByCategory", "analystLabeledFraudRecordsCompared",
                "analystLabeledFraudRecordsExcludedRulesEvidenceUnavailable"
        ), "rulesVsMlDisagreement");
        require("RISK_HIGH_OR_CRITICAL_POSITIVE_V1".equals(text(value, "classificationPolicy")),
                "disagreement classificationPolicy unsupported");
        int exact = integer(value, "recordsWithExactMlEvidence", MAX_RECORDS);
        int compared = integer(value, "recordsCompared", MAX_RECORDS);
        int excluded = integer(value, "recordsExcludedRulesEvidenceUnavailable", MAX_RECORDS);
        require(exact == recordsEvaluated && compared + excluded == exact, "disagreement counts do not reconcile");
        JsonNode categories = value.get("categoryCounts");
        Set<String> fields = Set.of("ML_HIGH_RULES_LOW", "RULES_HIGH_ML_LOW", "BOTH_HIGH", "BOTH_LOW");
        exactFields(categories, fields, "categoryCounts");
        int total = fields.stream().mapToInt(field -> integer(categories, field, MAX_RECORDS)).sum();
        require(total == compared, "categoryCounts do not reconcile");
        validateExpectedMetric(
                value.get("disagreementRate"),
                integer(categories, "ML_HIGH_RULES_LOW", MAX_RECORDS)
                        + integer(categories, "RULES_HIGH_ML_LOW", MAX_RECORDS),
                compared,
                "RULES_SIGNAL_UNAVAILABLE",
                "rulesVsMlDisagreement.disagreementRate"
        );
        JsonNode analystFraud = value.get("analystLabeledFraudByCategory");
        exactFields(analystFraud, fields, "analystLabeledFraudByCategory");
        int analystFraudTotal = fields.stream()
                .mapToInt(field -> integer(analystFraud, field, MAX_RECORDS))
                .sum();
        int analystFraudCompared = integer(value, "analystLabeledFraudRecordsCompared", MAX_RECORDS);
        int analystFraudUnavailable = integer(
                value, "analystLabeledFraudRecordsExcludedRulesEvidenceUnavailable", MAX_RECORDS);
        require(analystFraudTotal == analystFraudCompared,
                "analyst-labeled fraud category counts do not reconcile");
        require(analystFraudCompared + analystFraudUnavailable == positiveClassCount,
                "analyst-labeled fraud population does not reconcile");
        for (String field : fields) {
            require(integer(analystFraud, field, MAX_RECORDS) <= integer(categories, field, MAX_RECORDS),
                    "analyst-labeled fraud category exceeds category population");
        }
        validateAvailabilityFields(value, compared > 0, "RULES_SIGNAL_UNAVAILABLE");
    }

    private void validateAvailability(
            JsonNode value,
            boolean expectedAvailable,
            String unavailableReason,
            String label
    ) {
        exactFields(value, Set.of("available", "reason"), label);
        require(value.get("available").isBoolean(), label + ".available must be boolean");
        require(value.get("reason").isNull() || value.get("reason").isTextual(), label + ".reason malformed");
        validateAvailabilityFields(value, expectedAvailable, unavailableReason);
    }

    private void validateAvailabilityFields(JsonNode value, boolean expectedAvailable, String unavailableReason) {
        JsonNode available = value.get("available");
        require(available != null && available.isBoolean() && available.booleanValue() == expectedAvailable,
                "metric availability mismatch");
        JsonNode reason = value.get("reason");
        require(expectedAvailable ? reason != null && reason.isNull()
                        : reason != null && reason.isTextual() && unavailableReason.equals(reason.textValue()),
                "metric availability reason mismatch");
    }

    private void validateExpectedMetric(
            JsonNode metric,
            int numerator,
            int denominator,
            String unavailableReason,
            String label
    ) {
        require(matchesExpectedMetric(metric, numerator, denominator, unavailableReason),
                label + " is inconsistent");
    }

    private boolean matchesExpectedMetric(
            JsonNode metric,
            int numerator,
            int denominator,
            String unavailableReason
    ) {
        try {
            validateMetricShape(metric);
        } catch (ModelEvaluationValidationException exception) {
            return false;
        }
        if (denominator == 0) {
            return !metric.get("available").booleanValue()
                    && metric.get("value").isNull()
                    && metric.get("reason").isTextual()
                    && unavailableReason.equals(metric.get("reason").textValue());
        }
        if (!metric.get("available").booleanValue() || !metric.get("reason").isNull()) {
            return false;
        }
        JsonNode value = metric.get("value");
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            return false;
        }
        BigDecimal expected = roundedMetricValue(numerator, denominator);
        return value.decimalValue().compareTo(expected) == 0;
    }

    public static BigDecimal roundedMetricValue(int numerator, int denominator) {
        return new BigDecimal((double) numerator / denominator)
                .setScale(6, RoundingMode.HALF_EVEN)
                .stripTrailingZeros();
    }

    private void validateMetricShape(JsonNode metric) {
        exactFields(metric, Set.of("available", "reason", "value"), "metric value");
        require(metric.get("available").isBoolean(), "metric available must be boolean");
        require(metric.get("reason").isNull() || metric.get("reason").isTextual(),
                "metric reason must be text or null");
    }

    private record RankedPrefix(int actualK, int positiveCount) {
    }

    public record ExpectedSubject(
            String subjectType,
            String modelName,
            String modelVersion,
            String featureContractVersion,
            String modelArtifactSha256,
            String identityCompleteness
    ) {
    }

    public void validateExpectedWarnings(
            JsonNode warnings,
            JsonNode sourceDataset,
            Map<String, Integer> population,
            Map<String, Integer> balance
    ) {
        List<String> expected = new ArrayList<>();
        if (sourceDataset.get("truncated").booleanValue()) {
            expected.add("SOURCE_DATASET_TRUNCATED");
        }
        if (integer(sourceDataset, "skippedMissingRequiredFieldCount", MAX_RECORDS) > 0) {
            expected.add("SOURCE_DATASET_REQUIRED_FIELDS_MISSING");
        }
        if (integer(sourceDataset, "skippedInvalidSourceRecordCount", MAX_RECORDS) > 0) {
            expected.add("SOURCE_DATASET_INVALID_ROWS_SKIPPED");
        }
        int evaluated = population.get("recordsEvaluated");
        if (evaluated == 0) {
            expected.add("INSUFFICIENT_MODEL_LINEAGE_RECORDS");
            expected.add("MODEL_PREDICTION_SIGNAL_UNAVAILABLE");
        } else if (balance.get("positiveClassCount") == 0 || balance.get("negativeClassCount") == 0) {
            expected.add("SINGLE_CLASS_MODEL_LINEAGE_RECORDS");
        }
        if (population.get("recordsExcludedUnexpectedMissingPredictionEvidence") > 0) {
            expected.add("UNEXPECTED_ML_EVIDENCE_LOSS");
        }
        if (population.get("recordsExcludedInvalidPredictionEvidence") > 0) {
            expected.add("INVALID_ML_EVIDENCE_PRESENT");
        }
        if (evaluated < population.get("recordsConsidered")) {
            expected.add("MODEL_EVALUATION_PARTIAL_COVERAGE");
        }
        expected.sort(String::compareTo);
        require(warnings != null && warnings.isArray() && warnings.size() == expected.size(),
                "warnings do not match evaluated evidence");
        for (int index = 0; index < expected.size(); index++) {
            JsonNode warning = warnings.get(index);
            require(warning != null && warning.isTextual() && expected.get(index).equals(warning.textValue()),
                    "warnings do not match evaluated evidence");
        }
    }

    private void exactMachineCodes(JsonNode value, Set<String> allowed, boolean exact, String label) {
        require(value != null && value.isArray() && value.size() <= allowed.size(), label + " must be bounded");
        Set<String> observed = new HashSet<>();
        String previous = null;
        for (JsonNode item : value) {
            require(item.isTextual() && allowed.contains(item.textValue()), label + " contains unsupported value");
            require(observed.add(item.textValue()), label + " contains duplicates");
            require(previous == null || previous.compareTo(item.textValue()) < 0, label + " must be sorted");
            previous = item.textValue();
        }
        require(!exact || observed.equals(allowed), label + " is incomplete");
    }

    private void exactFields(JsonNode value, Set<String> expected, String label) {
        require(value != null && value.isObject() && value.size() == expected.size(), label + " field set unsupported");
        for (String field : expected) {
            require(value.has(field), label + " missing field");
        }
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isTextual(), field + " must be text");
        return node.textValue();
    }

    private int integer(JsonNode value, String field, int maximum) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isIntegralNumber() && node.canConvertToInt(), field + " must be an integer");
        int result = node.intValue();
        require(result >= 0 && result <= maximum, field + " is out of range");
        return result;
    }

    private void sha256(String value, String field) {
        require(value != null && SHA256.matcher(value).matches(), field + " must be lowercase SHA-256");
    }

    private void timestamp(String value, String field) {
        try {
            GovernanceTimestampContract.parse(value, field);
        } catch (IllegalArgumentException exception) {
            throw new ModelEvaluationValidationException(exception.getMessage());
        }
    }

    private void optionalTimestamp(JsonNode value, String field) {
        if (value != null && !value.isNull()) {
            require(value.isTextual(), field + " must be text or null");
            timestamp(value.textValue(), field);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ModelEvaluationValidationException(message);
        }
    }
}
