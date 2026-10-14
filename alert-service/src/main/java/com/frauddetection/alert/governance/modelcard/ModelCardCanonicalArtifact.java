package com.frauddetection.alert.governance.modelcard;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ModelCardCanonicalArtifact {

    private ModelCardCanonicalArtifact() {
    }

    static byte[] json(ObjectMapper objectMapper, JsonNode value) {
        try {
            return (objectMapper.writeValueAsString(sorted(objectMapper, value)) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException exception) {
            throw new ModelCardValidationException("model card canonical JSON unavailable");
        }
    }

    static byte[] markdown(JsonNode card) {
        JsonNode subject = card.get("subject");
        JsonNode evidence = card.get("evaluationEvidence");
        List<String> lines = new ArrayList<>(List.of(
                "# ML Model Card",
                "",
                "Exact-artifact offline diagnostic evidence. This card grants no production decision or promotion authority.",
                "",
                "## Model identity",
                "",
                line("Model name", subject, "modelName"),
                line("Model version", subject, "modelVersion"),
                line("Feature contract", subject, "featureContractVersion"),
                line("Artifact SHA-256", subject, "modelArtifactSha256"),
                line("Artifact verification", card.get("modelArtifactVerification"), "status"),
                "",
                "## Evaluation evidence",
                "",
                line("Status", evidence, "status")
        ));
        if ("AVAILABLE".equals(evidence.get("status").textValue())) {
            JsonNode summary = card.get("evaluationSummary");
            lines.addAll(List.of(
                    line("Report type", evidence, "reportType"),
                    line("Report generated at", evidence, "reportGeneratedAt"),
                    line("Report and manifest integrity", evidence, "integrityVerification"),
                    line("Source dataset verification", evidence, "sourceDatasetVerificationLevel"),
                    "",
                    "### Source dataset",
                    ""
            ));
            JsonNode source = summary.get("sourceDataset");
            lines.add(line("Dataset version", source, "datasetVersion"));
            lines.add(line("SHA-256", source, "sha256"));
            lines.add(line("Raw rows read", source, "rawRowsRead"));
            lines.add(line("Records returned", source, "recordsReturned"));
            lines.add(line("Unresolved rows excluded", source, "excludedUnresolvedCount"));
            lines.add(line("Governance-review rows excluded", source, "excludedGovernanceReviewCount"));
            lines.add(line("Missing-required-field rows skipped", source, "skippedMissingRequiredFieldCount"));
            lines.add(line("Invalid source rows skipped", source, "skippedInvalidSourceRecordCount"));
            lines.add(line("Truncated", source, "truncated"));
            lines.addAll(List.of("", "### Evaluation population", ""));
            summary.get("population").properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> lines.add("- " + entry.getKey() + ": " + code(entry.getValue())));
            lines.addAll(List.of(
                    "",
                    "### Class balance",
                    "",
                    line("Positive class count", summary.get("classBalance"), "positiveClassCount"),
                    line("Negative class count", summary.get("classBalance"), "negativeClassCount"),
                    "",
                    "### Metric availability",
                    ""
            ));
            summary.get("supportedMetrics").properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        JsonNode metric = entry.getValue();
                        JsonNode reason = metric.get("reason");
                        lines.add("- " + entry.getKey() + ": available=" + code(metric.get("available"))
                                + ", reason=" + code(reason.isNull() ? "NONE" : reason.textValue()));
                    });
            JsonNode prediction = summary.at("/supportedMetrics/mlPredictionMetrics");
            lines.addAll(List.of(
                    "",
                    "### Confusion matrix",
                    "",
                    line("True positive", prediction, "truePositive"),
                    line("False positive", prediction, "falsePositive"),
                    line("True negative", prediction, "trueNegative"),
                    line("False negative", prediction, "falseNegative"),
                    "",
                    "### Prediction metrics",
                    "",
                    metricLine("Precision", prediction.get("precision")),
                    metricLine("Recall", prediction.get("recall")),
                    metricLine("True-positive rate", prediction.get("truePositiveRate")),
                    metricLine("False-positive rate", prediction.get("falsePositiveRate")),
                    metricLine("False-negative rate", prediction.get("falseNegativeRate"))
            ));
            rankingSection(lines, "Precision at K", prediction.get("precisionAtK"));
            rankingSection(lines, "Recall at K", prediction.get("recallAtK"));
            JsonNode disagreement = summary.at("/supportedMetrics/rulesVsMlDisagreement");
            JsonNode disagreementReason = disagreement.get("reason");
            lines.addAll(List.of(
                    "",
                    "### Rules-vs-ML disagreement",
                    "",
                    "- Available: " + code(disagreement.get("available")),
                    "- Reason: " + code(disagreementReason.isNull() ? "NONE" : disagreementReason.textValue()),
                    line("Records with exact ML evidence", disagreement, "recordsWithExactMlEvidence"),
                    line("Records compared", disagreement, "recordsCompared"),
                    line("Records excluded because Rules evidence was unavailable", disagreement,
                            "recordsExcludedRulesEvidenceUnavailable")
            ));
            disagreement.get("categoryCounts").properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> lines.add("- " + entry.getKey() + ": " + code(entry.getValue())));
            machineCodeSection(lines, "Warnings", summary.get("warnings"));
            machineCodeSection(lines, "Limitations", summary.get("limitations"));
        } else {
            lines.add(line("Reason", evidence, "reason"));
        }
        JsonNode boundary = card.get("verifiedUsageBoundary");
        List<String> modes = new ArrayList<>();
        boundary.get("allowedModes").forEach(mode -> modes.add(mode.textValue()));
        lines.addAll(List.of(
                "",
                "## Verified usage boundary",
                "",
                "- Allowed modes: `" + String.join(", ", modes) + "`",
                line("Promotion state", card, "promotionState")
        ));
        machineCodeSection(lines, "Intended use", boundary.get("intendedUse"));
        machineCodeSection(lines, "Not intended use", boundary.get("notIntendedUse"));
        machineCodeSection(lines, "Use limitations", boundary.get("useLimitations"));
        lines.addAll(List.of(
                "",
                "- Runtime decision authority: `NONE`",
                "- Promotion authority: `NONE`",
                "- Threshold change authority: `NONE`",
                "- Payment authorization authority: `NONE`",
                "- Workflow authority: `NONE`",
                ""
        ));
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode sorted(ObjectMapper objectMapper, JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            value.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), sorted(objectMapper, entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(sorted(objectMapper, item)));
            return result;
        }
        return value;
    }

    private static String line(String label, JsonNode parent, String field) {
        return "- " + label + ": " + code(parent.get(field));
    }

    private static void machineCodeSection(List<String> lines, String title, JsonNode values) {
        lines.add("");
        lines.add("### " + title);
        lines.add("");
        if (values.isEmpty()) {
            lines.add("- `NONE`");
            return;
        }
        values.forEach(value -> lines.add("- " + code(value)));
    }

    private static String metricLine(String label, JsonNode metric) {
        JsonNode reason = metric.get("reason");
        String value = metric.get("available").booleanValue() ? metric.get("value").asText() : "UNAVAILABLE";
        return "- " + label + ": available=" + code(metric.get("available"))
                + ", value=" + code(value)
                + ", reason=" + code(reason.isNull() ? "NONE" : reason.textValue());
    }

    private static void rankingSection(List<String> lines, String title, JsonNode rows) {
        lines.addAll(List.of("", "### " + title, ""));
        rows.properties().stream()
                .sorted(Map.Entry.comparingByKey((left, right) -> Integer.compare(
                        Integer.parseInt(left), Integer.parseInt(right))))
                .forEach(entry -> {
                    JsonNode row = entry.getValue();
                    JsonNode metric = row.get("value");
                    JsonNode reason = metric.get("reason");
                    String value = metric.get("available").booleanValue()
                            ? metric.get("value").asText()
                            : "UNAVAILABLE";
                    lines.add("- K " + entry.getKey()
                            + ": requestedK=" + code(row.get("requestedK"))
                            + ", actualK=" + code(row.get("actualK"))
                            + ", available=" + code(metric.get("available"))
                            + ", value=" + code(value)
                            + ", reason=" + code(reason.isNull() ? "NONE" : reason.textValue()));
                });
    }

    private static String code(JsonNode value) {
        return code(value.asText());
    }

    private static String code(String value) {
        if (value.indexOf('`') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new ModelCardValidationException("model card markdown value contains unsupported control characters");
        }
        return "`" + value + "`";
    }
}
