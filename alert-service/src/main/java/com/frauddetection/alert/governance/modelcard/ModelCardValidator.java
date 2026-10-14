package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ModelCardValidator {

    private static final String CARD_SCHEMA = "ml-model-card-v1";
    private static final String CARD_TYPE = "ML_MODEL_CARD_V1";
    private static final String EVALUATION_REPORT_TYPE = "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1";
    private static final String EVALUATION_ARTIFACT_SET =
            "ml-model-feedback-dataset-evaluation-artifact-set-v1";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern IDENTITY = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z$"
    );
    private static final int MAX_RECORDS = 1_000;
    private static final Set<String> FORBIDDEN_IDENTITY_TERMS = Set.of(
            "accountid", "apikey", "authorization", "bearer", "cardid", "correlationid", "customerid",
            "deviceid", "email", "endpoint", "exceptionmessage", "feedbackid", "finaldecision",
            "groundtruth", "idempotencykey", "merchantid", "metadata", "modeltraininglabel", "password",
            "paymentauthorization", "rawfeaturevector", "rawmlrequest", "rawmlresponse", "rawpayload",
            "rawrequest", "rawresponse", "requestpayloadhash", "secret", "stacktrace", "submittedby",
            "token", "traininglabel", "transactionid"
    );
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
    private static final Set<String> WARNINGS = Set.of(
            "INSUFFICIENT_MODEL_LINEAGE_RECORDS", "SINGLE_CLASS_MODEL_LINEAGE_RECORDS",
            "MODEL_PREDICTION_SIGNAL_UNAVAILABLE", "UNEXPECTED_ML_EVIDENCE_LOSS",
            "INVALID_ML_EVIDENCE_PRESENT", "MODEL_EVALUATION_PARTIAL_COVERAGE",
            "SOURCE_DATASET_TRUNCATED", "SOURCE_DATASET_REQUIRED_FIELDS_MISSING",
            "SOURCE_DATASET_INVALID_ROWS_SKIPPED"
    );

    public void validate(ModelCard card) {
        require(card != null, "model card is missing");
        require(CARD_SCHEMA.equals(card.schemaVersion()), "schemaVersion unsupported");
        require(CARD_TYPE.equals(card.cardType()), "cardType unsupported");
        timestamp(card.generatedAt(), "generatedAt");
        validateSubject(card.subject());
        require("NOT_EVALUATED_FOR_PROMOTION".equals(card.promotionState()), "promotionState unsupported");
        validateArtifactVerification(card);
        validateEvidenceAndSummary(card);
        validateUsageBoundary(card.verifiedUsageBoundary());
    }

    private void validateSubject(ModelCard.Subject subject) {
        require(subject != null, "subject is missing");
        require("ML_MODEL".equals(subject.subjectType()), "subjectType unsupported");
        identity(subject.modelName(), 64, "modelName");
        identity(subject.modelVersion(), 64, "modelVersion");
        identity(subject.featureContractVersion(), 96, "featureContractVersion");
        sha256(subject.modelArtifactSha256(), "modelArtifactSha256");
        require("COMPLETE".equals(subject.identityCompleteness()), "identityCompleteness unsupported");
    }

    private void validateArtifactVerification(ModelCard card) {
        ModelCard.ModelArtifactVerification verification = card.modelArtifactVerification();
        require(verification != null, "modelArtifactVerification is missing");
        require("VERIFIED_AGAINST_ARTIFACT_BYTES".equals(verification.status()),
                "modelArtifactVerification.status unsupported");
        require(card.subject().modelArtifactSha256().equals(verification.sha256()),
                "model artifact verification does not match subject");
    }

    private void validateEvidenceAndSummary(ModelCard card) {
        JsonNode evidence = card.evaluationEvidence();
        require(evidence != null && evidence.isObject(), "evaluationEvidence must be an object");
        String status = text(evidence, "status");
        if ("NOT_AVAILABLE".equals(status)) {
            exactFields(evidence, Set.of("status", "reason"), "evaluationEvidence");
            require("MODEL_EVALUATION_NOT_CONFIGURED".equals(text(evidence, "reason")),
                    "evaluationEvidence.reason unsupported");
            require(card.evaluationSummary() == null || card.evaluationSummary().isNull(),
                    "evaluationSummary must be null when evaluation is unavailable");
            return;
        }
        require("AVAILABLE".equals(status), "evaluationEvidence.status unsupported");
        exactFields(evidence, Set.of(
                "status", "reportType", "artifactSetVersion", "reportGeneratedAt", "manifestSha256",
                "integrityVerification", "sourceDatasetVerificationLevel"
        ), "evaluationEvidence");
        require(EVALUATION_REPORT_TYPE.equals(text(evidence, "reportType")),
                "evaluationEvidence.reportType unsupported");
        require(EVALUATION_ARTIFACT_SET.equals(text(evidence, "artifactSetVersion")),
                "evaluationEvidence.artifactSetVersion unsupported");
        timestamp(text(evidence, "reportGeneratedAt"), "evaluationEvidence.reportGeneratedAt");
        sha256(text(evidence, "manifestSha256"), "evaluationEvidence.manifestSha256");
        require("VERIFIED_REPORT_AND_MANIFEST".equals(text(evidence, "integrityVerification")),
                "evaluationEvidence.integrityVerification unsupported");
        require(Set.of("CLAIM_ONLY", "VERIFIED_AGAINST_SOURCE_BYTES")
                        .contains(text(evidence, "sourceDatasetVerificationLevel")),
                "evaluationEvidence.sourceDatasetVerificationLevel unsupported");
        validateSummary(card, evidence);
    }

    private void validateSummary(ModelCard card, JsonNode evidence) {
        JsonNode summary = card.evaluationSummary();
        require(summary != null && summary.isObject(), "evaluationSummary must be an object");
        exactFields(summary, SUMMARY_FIELDS, "evaluationSummary");
        require(EVALUATION_REPORT_TYPE.equals(text(summary, "reportType")),
                "evaluationSummary.reportType unsupported");
        require("BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_ARTIFACT".equals(text(summary, "metricBasis")),
                "evaluationSummary.metricBasis unsupported");
        String summaryGeneratedAt = text(summary, "generatedAt");
        timestamp(summaryGeneratedAt, "evaluationSummary.generatedAt");
        require(summaryGeneratedAt.equals(text(evidence, "reportGeneratedAt")),
                "evaluation report timestamp mismatch");
        require(!Instant.parse(card.generatedAt()).isBefore(Instant.parse(summaryGeneratedAt)),
                "model card predates evaluation");
        validateSummarySubject(summary.get("evaluationSubject"), card.subject());
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
        exactMachineCodes(summary.get("warnings"), WARNINGS, false, "warnings");
    }

    private void validateSummarySubject(JsonNode subject, ModelCard.Subject expected) {
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

    void validateWindow(JsonNode window) {
        exactFields(window, Set.of("timeBasis", "fromInclusive", "toInclusive"), "evaluationWindow");
        require("FEEDBACK_CREATED_AT".equals(text(window, "timeBasis")), "evaluationWindow.timeBasis unsupported");
        optionalTimestamp(window.get("fromInclusive"), "evaluationWindow.fromInclusive");
        optionalTimestamp(window.get("toInclusive"), "evaluationWindow.toInclusive");
        JsonNode fromInclusive = window.get("fromInclusive");
        JsonNode toInclusive = window.get("toInclusive");
        if (!fromInclusive.isNull() && !toInclusive.isNull()) {
            require(!Instant.parse(fromInclusive.textValue()).isAfter(Instant.parse(toInclusive.textValue())),
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
        validateDisagreement(metrics.get("rulesVsMlDisagreement"), population.get("recordsEvaluated"));
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
        require("RISK_HIGH_OR_CRITICAL_POSITIVE_V1".equals(text(metrics, "classificationPolicy")),
                "classificationPolicy unsupported");
        require("EXACT_ML_PREDICTION_SCORE_DESC_EVALUATION_RECORD_ID_ASC_V1"
                        .equals(text(metrics, "rankingPolicy")), "rankingPolicy unsupported");
        validateAvailabilityFields(metrics, recordsEvaluated > 0, "MODEL_PREDICTION_SIGNAL_UNAVAILABLE");
        for (String field : List.of(
                "precision", "recall", "truePositiveRate", "falsePositiveRate", "falseNegativeRate"
        )) {
            validateMetricValue(metrics.get(field), "mlPredictionMetrics." + field);
        }
        validateRanking(metrics.get("precisionAtK"), "precisionAtK", recordsEvaluated);
        validateRanking(metrics.get("recallAtK"), "recallAtK", recordsEvaluated);
        require(metrics.get("precisionAtK").size() == metrics.get("recallAtK").size(),
                "ranking metric key sets differ");
        for (Map.Entry<String, JsonNode> entry : metrics.get("precisionAtK").properties()) {
            require(metrics.get("recallAtK").has(entry.getKey()), "ranking metric key sets differ");
        }
    }

    private void validateRanking(JsonNode ranking, String label, int recordsEvaluated) {
        require(ranking != null && ranking.isObject() && !ranking.isEmpty(), label + " must be an object");
        require(ranking.size() <= 32, label + " contains too many K values");
        for (Map.Entry<String, JsonNode> entry : ranking.properties()) {
            JsonNode row = entry.getValue();
            exactFields(row, Set.of("requestedK", "actualK", "value"), label + " row");
            int requested = integer(row, "requestedK", MAX_RECORDS);
            require(requested > 0 && entry.getKey().equals(Integer.toString(requested)), label + " key mismatch");
            require(integer(row, "actualK", MAX_RECORDS) == Math.min(requested, recordsEvaluated),
                    label + " actualK mismatch");
            validateMetricValue(row.get("value"), label + ".value");
        }
    }

    private void validateDisagreement(JsonNode value, int recordsEvaluated) {
        exactFields(value, Set.of(
                "available", "reason", "classificationPolicy", "recordsWithExactMlEvidence", "recordsCompared",
                "recordsExcludedRulesEvidenceUnavailable", "categoryCounts"
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

    private void validateMetricValue(JsonNode metric, String label) {
        exactFields(metric, Set.of("available", "reason", "value"), label);
        require(metric.get("available").isBoolean(), label + ".available must be boolean");
        if (metric.get("available").booleanValue()) {
            require(metric.get("reason").isNull(), label + ".reason must be null");
            JsonNode value = metric.get("value");
            require(value.isNumber() && Double.isFinite(value.doubleValue())
                    && value.doubleValue() >= 0.0 && value.doubleValue() <= 1.0, label + ".value out of range");
        } else {
            require(metric.get("reason").isTextual() && !metric.get("reason").textValue().isBlank(),
                    label + ".reason missing");
            require(metric.get("value").isNull(), label + ".value must be null");
        }
    }

    private void validateUsageBoundary(ModelCard.VerifiedUsageBoundary boundary) {
        require(boundary != null, "verifiedUsageBoundary is missing");
        require(List.of("COMPARE", "OFFLINE_EVALUATION", "SHADOW").equals(boundary.allowedModes()),
                "verifiedUsageBoundary.allowedModes unsupported");
        require(List.of(
                boundary.runtimeDecisionAuthority(), boundary.promotionAuthority(),
                boundary.thresholdChangeAuthority(), boundary.paymentAuthorizationAuthority(),
                boundary.workflowAuthority()
        ).stream().allMatch("NONE"::equals), "verified usage authority must be NONE");
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

    private void identity(String value, int maximum, String field) {
        require(value != null && !value.isBlank() && value.length() <= maximum && IDENTITY.matcher(value).matches(),
                field + " is invalid");
        String compact = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        require(FORBIDDEN_IDENTITY_TERMS.stream().noneMatch(compact::contains), field + " contains forbidden value");
    }

    private void sha256(String value, String field) {
        require(value != null && SHA256.matcher(value).matches(), field + " must be lowercase SHA-256");
    }

    private void timestamp(String value, String field) {
        require(value != null && value.length() <= 128 && TIMESTAMP.matcher(value).matches(),
                field + " must be a canonical timestamp");
        try {
            Instant.parse(value);
        } catch (DateTimeException exception) {
            throw new ModelCardValidationException(field + " must be a valid timestamp");
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
            throw new ModelCardValidationException(message);
        }
    }
}
