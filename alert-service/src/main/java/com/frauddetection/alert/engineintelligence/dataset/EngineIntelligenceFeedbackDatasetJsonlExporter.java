package com.frauddetection.alert.engineintelligence.dataset;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;


import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class EngineIntelligenceFeedbackDatasetJsonlExporter {

    private final ObjectMapper objectMapper;

    public EngineIntelligenceFeedbackDatasetJsonlExporter() {
        this(JsonMapper.builder().findAndAddModules().build());
    }

    EngineIntelligenceFeedbackDatasetJsonlExporter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public String exportJsonl(EngineIntelligenceFeedbackDatasetExportResult exportResult) {
        Objects.requireNonNull(exportResult, "exportResult is required");
        StringBuilder jsonl = new StringBuilder();
        appendLine(jsonl, metadata(exportResult));
        if (exportResult.failed()) {
            return jsonl.toString();
        }
        for (EngineIntelligenceFeedbackDatasetRecord record : exportResult.records()) {
            appendLine(jsonl, recordLine(record));
        }
        return jsonl.toString();
    }

    private Map<String, Object> metadata(EngineIntelligenceFeedbackDatasetExportResult exportResult) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "EXPORT_METADATA");
        metadata.put("fromInclusive", exportResult.fromInclusive());
        metadata.put("toInclusive", exportResult.toInclusive());
        metadata.put("exportedAt", exportResult.exportedAt());
        metadata.put("maxRecords", exportResult.maxRecords());
        metadata.put("rawRowsRead", exportResult.rawRowsRead());
        metadata.put("recordsReturned", exportResult.recordsReturned());
        metadata.put("truncated", exportResult.truncated());
        metadata.put("timeBasis", exportResult.timeBasis());
        metadata.put("deduplicationPolicy", exportResult.deduplicationPolicy());
        metadata.put("failureReason", exportResult.failureReason());
        return metadata;
    }

    private Map<String, Object> recordLine(EngineIntelligenceFeedbackDatasetRecord record) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "DATASET_RECORD");
        line.put("record", record);
        return line;
    }

    private void appendLine(StringBuilder jsonl, Map<String, Object> line) {
        try {
            jsonl.append(objectMapper.writeValueAsString(line)).append('\n');
        } catch (JacksonException exception) {
            throw new EngineIntelligenceFeedbackDatasetSerializationException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.SERIALIZATION_FAILED
            );
        }
    }

    static class EngineIntelligenceFeedbackDatasetSerializationException extends RuntimeException {

        private final EngineIntelligenceFeedbackDatasetExportFailureReason reason;

        EngineIntelligenceFeedbackDatasetSerializationException(
                EngineIntelligenceFeedbackDatasetExportFailureReason reason
        ) {
            super(reason.name());
            this.reason = reason;
        }

        EngineIntelligenceFeedbackDatasetExportFailureReason reason() {
            return reason;
        }
    }
}
