package com.frauddetection.alert.feedback.dataset;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FeedbackDatasetBuildResult(
        String datasetVersion,
        Instant builtAt,
        FeedbackDatasetTimeBasis timeBasis,
        Instant fromInclusive,
        Instant toInclusive,
        int rawRowsRead,
        int recordsReturned,
        int excludedUnresolvedCount,
        int excludedGovernanceReviewCount,
        int skippedMissingRequiredFieldCount,
        int skippedInvalidSourceRecordCount,
        boolean truncated,
        FeedbackDatasetBuildFailureReason failureReason,
        List<FeedbackDatasetRecord> records
) {

    public FeedbackDatasetBuildResult {
        datasetVersion = Objects.requireNonNull(datasetVersion, "datasetVersion is required");
        builtAt = Objects.requireNonNull(builtAt, "builtAt is required");
        timeBasis = Objects.requireNonNull(timeBasis, "timeBasis is required");
        failureReason = failureReason == null ? FeedbackDatasetBuildFailureReason.NONE : failureReason;
        records = records == null ? List.of() : List.copyOf(records);
        if (rawRowsRead < 0 || recordsReturned < 0 || excludedUnresolvedCount < 0
                || excludedGovernanceReviewCount < 0 || skippedMissingRequiredFieldCount < 0
                || skippedInvalidSourceRecordCount < 0) {
            throw new IllegalArgumentException("dataset counts must not be negative");
        }
        if (failureReason != FeedbackDatasetBuildFailureReason.NONE) {
            if (!records.isEmpty() || recordsReturned != 0) {
                throw new IllegalArgumentException("failed build must not contain dataset records");
            }
            if (rawRowsRead != 0 || truncated) {
                throw new IllegalArgumentException("failed build must not report successful store results");
            }
        }
        if (recordsReturned != records.size()) {
            throw new IllegalArgumentException("recordsReturned must match records size");
        }
    }

    public boolean failed() {
        return failureReason != FeedbackDatasetBuildFailureReason.NONE;
    }

    static FeedbackDatasetBuildResult succeeded(
            FeedbackDatasetBuildRequest request,
            Instant builtAt,
            int rawRowsRead,
            int recordsReturned,
            int excludedUnresolvedCount,
            int excludedGovernanceReviewCount,
            int skippedMissingRequiredFieldCount,
            int skippedInvalidSourceRecordCount,
            boolean truncated,
            List<FeedbackDatasetRecord> records
    ) {
        return new FeedbackDatasetBuildResult(
                FeedbackDatasetBuilder.DATASET_VERSION,
                builtAt,
                FeedbackDatasetTimeBasis.FEEDBACK_CREATED_AT,
                request.fromInclusive(),
                request.toInclusive(),
                rawRowsRead,
                recordsReturned,
                excludedUnresolvedCount,
                excludedGovernanceReviewCount,
                skippedMissingRequiredFieldCount,
                skippedInvalidSourceRecordCount,
                truncated,
                FeedbackDatasetBuildFailureReason.NONE,
                records
        );
    }

    static FeedbackDatasetBuildResult failed(
            FeedbackDatasetBuildRequest request,
            Instant builtAt,
            FeedbackDatasetBuildFailureReason reason
    ) {
        return new FeedbackDatasetBuildResult(
                FeedbackDatasetBuilder.DATASET_VERSION,
                builtAt,
                FeedbackDatasetTimeBasis.FEEDBACK_CREATED_AT,
                request == null ? null : request.fromInclusive(),
                request == null ? null : request.toInclusive(),
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                Objects.requireNonNull(reason, "reason is required"),
                List.of()
        );
    }
}
