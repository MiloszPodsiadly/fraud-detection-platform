package com.frauddetection.alert.feedback.dataset;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class FeedbackDatasetJsonlWriter {

    private final ObjectMapper objectMapper;

    public FeedbackDatasetJsonlWriter() {
        this(JsonMapper.builder().findAndAddModules().build());
    }

    FeedbackDatasetJsonlWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public String writeJsonl(FeedbackDatasetBuildResult result) {
        Objects.requireNonNull(result, "result is required");
        StringBuilder jsonl = new StringBuilder();
        appendLine(jsonl, metadata(result));
        if (result.failed()) {
            return jsonl.toString();
        }
        for (FeedbackDatasetRecord record : result.records()) {
            appendLine(jsonl, recordLine(record));
        }
        return jsonl.toString();
    }

    private Map<String, Object> metadata(FeedbackDatasetBuildResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "DATASET_METADATA");
        metadata.put("datasetVersion", result.datasetVersion());
        metadata.put("builtAt", result.builtAt());
        metadata.put("timeBasis", result.timeBasis());
        metadata.put("fromInclusive", result.fromInclusive());
        metadata.put("toInclusive", result.toInclusive());
        metadata.put("rawRowsRead", result.rawRowsRead());
        metadata.put("recordsReturned", result.recordsReturned());
        metadata.put("excludedUnresolvedCount", result.excludedUnresolvedCount());
        metadata.put("excludedGovernanceReviewCount", result.excludedGovernanceReviewCount());
        metadata.put("skippedMissingRequiredFieldCount", result.skippedMissingRequiredFieldCount());
        metadata.put("truncated", result.truncated());
        metadata.put("failureReason", result.failureReason());
        return metadata;
    }

    private Map<String, Object> recordLine(FeedbackDatasetRecord record) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "DATASET_RECORD");
        line.put("record", record);
        return line;
    }

    private void appendLine(StringBuilder jsonl, Map<String, Object> line) {
        try {
            jsonl.append(objectMapper.writeValueAsString(line)).append('\n');
        } catch (JacksonException exception) {
            throw new FeedbackDatasetSerializationException();
        }
    }

    static class FeedbackDatasetSerializationException extends RuntimeException {

        FeedbackDatasetSerializationException() {
            super(FeedbackDatasetBuildFailureReason.DATASET_SERIALIZATION_FAILED.name());
        }
    }
}
