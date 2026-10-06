package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;

import java.util.Objects;
import java.util.Optional;

record FeedbackDatasetMlPredictionEvidence(
        FeedbackDatasetMlPredictionEvidenceStatus status,
        Optional<MlPredictionEvidenceProjection> projection,
        Optional<MlPredictionEvidenceOmissionReason> omissionReason
) {

    FeedbackDatasetMlPredictionEvidence {
        status = Objects.requireNonNull(status, "status is required");
        projection = Objects.requireNonNull(projection, "projection is required");
        omissionReason = Objects.requireNonNull(omissionReason, "omissionReason is required");
        if ((status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE) != projection.isPresent()) {
            throw new IllegalArgumentException("only available ML prediction evidence may carry a projection");
        }
        if (projection.isPresent() && omissionReason.isPresent()) {
            throw new IllegalArgumentException("ML prediction evidence requires exactly one authoritative outcome");
        }
        if (omissionReason.isPresent()
                && status != FeedbackDatasetMlPredictionEvidenceStatus.fromAuthoritativeOmission(
                        omissionReason.orElseThrow()
                )) {
            throw new IllegalArgumentException("ML prediction omission reason contradicts evidence status");
        }
    }

    static FeedbackDatasetMlPredictionEvidence available(MlPredictionEvidenceProjection projection) {
        return new FeedbackDatasetMlPredictionEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE,
                Optional.of(Objects.requireNonNull(projection, "projection is required")),
                Optional.empty()
        );
    }

    static FeedbackDatasetMlPredictionEvidence omitted(MlPredictionEvidenceOmissionReason reason) {
        MlPredictionEvidenceOmissionReason required = Objects.requireNonNull(reason, "reason is required");
        return new FeedbackDatasetMlPredictionEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.fromAuthoritativeOmission(required),
                Optional.empty(),
                Optional.of(required)
        );
    }

    static FeedbackDatasetMlPredictionEvidence unavailable(FeedbackDatasetMlPredictionEvidenceStatus status) {
        if (status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE) {
            throw new IllegalArgumentException("available evidence requires a projection");
        }
        return new FeedbackDatasetMlPredictionEvidence(status, Optional.empty(), Optional.empty());
    }
}
