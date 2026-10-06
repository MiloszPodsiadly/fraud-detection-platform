package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;

import java.util.Objects;
import java.util.Optional;

record FeedbackDatasetMlPredictionEvidence(
        FeedbackDatasetMlPredictionEvidenceStatus status,
        Optional<MlPredictionEvidenceProjection> projection
) {

    FeedbackDatasetMlPredictionEvidence {
        status = Objects.requireNonNull(status, "status is required");
        projection = Objects.requireNonNull(projection, "projection is required");
        if ((status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE) != projection.isPresent()) {
            throw new IllegalArgumentException("only available ML prediction evidence may carry a projection");
        }
    }

    static FeedbackDatasetMlPredictionEvidence available(MlPredictionEvidenceProjection projection) {
        return new FeedbackDatasetMlPredictionEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE,
                Optional.of(Objects.requireNonNull(projection, "projection is required"))
        );
    }

    static FeedbackDatasetMlPredictionEvidence unavailable(FeedbackDatasetMlPredictionEvidenceStatus status) {
        if (status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE) {
            throw new IllegalArgumentException("available evidence requires a projection");
        }
        return new FeedbackDatasetMlPredictionEvidence(status, Optional.empty());
    }

    boolean mayEnterDataset() {
        return status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE
                || status == FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT;
    }
}
