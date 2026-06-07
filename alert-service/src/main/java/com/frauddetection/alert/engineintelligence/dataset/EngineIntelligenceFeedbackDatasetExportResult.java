package com.frauddetection.alert.engineintelligence.dataset;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record EngineIntelligenceFeedbackDatasetExportResult(
        Instant fromInclusive,
        Instant toInclusive,
        Instant exportedAt,
        int maxRecords,
        int rawRowsRead,
        int recordsReturned,
        boolean truncated,
        EngineIntelligenceFeedbackDatasetTimeBasis timeBasis,
        EngineIntelligenceFeedbackDatasetDeduplicationPolicy deduplicationPolicy,
        EngineIntelligenceFeedbackDatasetExportFailureReason failureReason,
        List<EngineIntelligenceFeedbackDatasetRecord> records
) {

    public EngineIntelligenceFeedbackDatasetExportResult {
        fromInclusive = Objects.requireNonNull(fromInclusive, "fromInclusive is required");
        toInclusive = Objects.requireNonNull(toInclusive, "toInclusive is required");
        exportedAt = Objects.requireNonNull(exportedAt, "exportedAt is required");
        timeBasis = Objects.requireNonNull(timeBasis, "timeBasis is required");
        deduplicationPolicy = Objects.requireNonNull(deduplicationPolicy, "deduplicationPolicy is required");
        if (maxRecords < EngineIntelligenceFeedbackDatasetExportRequest.MIN_RECORDS
                || maxRecords > EngineIntelligenceFeedbackDatasetExportRequest.MAX_RECORDS) {
            throw new IllegalArgumentException("maxRecords must be between 1 and 500");
        }
        if (rawRowsRead < 0 || recordsReturned < 0) {
            throw new IllegalArgumentException("record counts must not be negative");
        }
        records = records == null ? List.of() : List.copyOf(records);
        if (recordsReturned != records.size()) {
            throw new IllegalArgumentException("recordsReturned must match records size");
        }
        if (recordsReturned > maxRecords) {
            throw new IllegalArgumentException("recordsReturned must not exceed maxRecords");
        }
    }

    public boolean failed() {
        return failureReason != null;
    }

    static EngineIntelligenceFeedbackDatasetExportResult succeeded(
            EngineIntelligenceFeedbackDatasetExportRequest request,
            Instant exportedAt,
            int rawRowsRead,
            boolean truncated,
            List<EngineIntelligenceFeedbackDatasetRecord> records
    ) {
        return new EngineIntelligenceFeedbackDatasetExportResult(
                request.fromInclusive(),
                request.toInclusive(),
                exportedAt,
                request.maxRecords(),
                rawRowsRead,
                records.size(),
                truncated,
                EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT,
                EngineIntelligenceFeedbackDatasetDeduplicationPolicy.TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC,
                null,
                records
        );
    }

    static EngineIntelligenceFeedbackDatasetExportResult failed(
            EngineIntelligenceFeedbackDatasetExportRequest request,
            Instant exportedAt,
            EngineIntelligenceFeedbackDatasetExportFailureReason reason
    ) {
        return new EngineIntelligenceFeedbackDatasetExportResult(
                request.fromInclusive(),
                request.toInclusive(),
                exportedAt,
                request.maxRecords(),
                0,
                0,
                false,
                EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT,
                EngineIntelligenceFeedbackDatasetDeduplicationPolicy.TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC,
                Objects.requireNonNull(reason, "reason is required"),
                List.of()
        );
    }
}
