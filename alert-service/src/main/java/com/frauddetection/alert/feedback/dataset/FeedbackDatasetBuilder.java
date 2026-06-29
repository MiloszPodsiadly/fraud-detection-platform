package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.feedback.governance.FeedbackDatasetEligibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class FeedbackDatasetBuilder {

    public static final String DATASET_VERSION = "feedback-dataset-v1";

    private final FeedbackDatasetCandidateStore candidateStore;
    private final FeedbackDatasetMappingPolicy mappingPolicy;
    private final Clock clock;

    @Autowired
    public FeedbackDatasetBuilder(
            FeedbackDatasetCandidateStore candidateStore,
            FeedbackDatasetMappingPolicy mappingPolicy
    ) {
        this(candidateStore, mappingPolicy, Clock.systemUTC());
    }

    FeedbackDatasetBuilder(
            FeedbackDatasetCandidateStore candidateStore,
            FeedbackDatasetMappingPolicy mappingPolicy,
            Clock clock
    ) {
        this.candidateStore = Objects.requireNonNull(candidateStore, "candidateStore is required");
        this.mappingPolicy = Objects.requireNonNull(mappingPolicy, "mappingPolicy is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public FeedbackDatasetBuildResult build(FeedbackDatasetBuildRequest request) {
        Instant builtAt = Instant.now(clock);
        if (!valid(request)) {
            return FeedbackDatasetBuildResult.failed(
                    request,
                    builtAt,
                    FeedbackDatasetBuildFailureReason.INVALID_REQUEST
            );
        }
        int maxRecords = request.effectiveMaxRecords();
        List<FraudFeedbackRecord> rawRows;
        try {
            rawRows = candidateStore.findBoundedByCreatedAt(request.fromInclusive(), request.toInclusive(), maxRecords);
        } catch (RuntimeException exception) {
            return FeedbackDatasetBuildResult.failed(
                    request,
                    builtAt,
                    FeedbackDatasetBuildFailureReason.FEEDBACK_STORE_UNAVAILABLE
            );
        }

        boolean truncated = rawRows.size() > maxRecords;
        List<FraudFeedbackRecord> boundedRows = rawRows.stream().limit(maxRecords).toList();
        int excludedUnresolved = 0;
        int excludedGovernanceReview = 0;
        int skippedMissingRequired = 0;
        List<FeedbackDatasetRecord> records = new ArrayList<>();

        for (FraudFeedbackRecord source : boundedRows) {
            FeedbackDatasetEligibility eligibility = mappingPolicy.eligibilityFor(source.getFeedbackLabel());
            if (eligibility == FeedbackDatasetEligibility.UNRESOLVED_EXCLUDED) {
                excludedUnresolved++;
                continue;
            }
            if (eligibility != FeedbackDatasetEligibility.EVALUATION_CANDIDATE) {
                excludedGovernanceReview++;
                continue;
            }
            try {
                mappingPolicy.evaluationLabel(source.getFeedbackLabel())
                        .map(label -> record(source, label))
                        .ifPresent(records::add);
            } catch (IllegalArgumentException exception) {
                skippedMissingRequired++;
            }
        }

        return FeedbackDatasetBuildResult.succeeded(
                request,
                builtAt,
                rawRows.size(),
                records.size(),
                excludedUnresolved,
                excludedGovernanceReview,
                skippedMissingRequired,
                truncated,
                records
        );
    }

    private boolean valid(FeedbackDatasetBuildRequest request) {
        if (request == null
                || request.fromInclusive() == null
                || request.toInclusive() == null
                || request.fromInclusive().isAfter(request.toInclusive())) {
            return false;
        }
        if (Duration.between(request.fromInclusive(), request.toInclusive())
                .compareTo(Duration.ofDays(FeedbackDatasetBuildRequest.MAX_RANGE_DAYS)) > 0) {
            return false;
        }
        return request.maxRecords() == null || request.maxRecords() > 0;
    }

    private FeedbackDatasetRecord record(FraudFeedbackRecord source, FeedbackEvaluationLabel evaluationLabel) {
        return new FeedbackDatasetRecord(
                DATASET_VERSION,
                FeedbackDatasetIdentifierHasher.evaluationRecordId(source.getFeedbackId()),
                FeedbackDatasetIdentifierHasher.transactionReference(source.getTransactionId()),
                source.getFeedbackLabel(),
                evaluationLabel,
                source.getDecisionReasonCodes(),
                requireCreatedAt(source),
                source.getFraudScore(),
                source.getRiskLevel(),
                source.getAlertRecommended(),
                source.getEngineIntelligenceStatus(),
                source.getAgreementStatus(),
                source.getRiskMismatchStatus(),
                source.getScoreDeltaBucket(),
                source.getAnalystRecommendationStatus(),
                source.getAnalystRecommendation(),
                source.getAnalystRecommendationVersion(),
                source.getAnalystRecommendationGeneratedAt(),
                source.getAnalystRecommendationReasonCodes(),
                source.getScoredAt(),
                source.getTransactionTimestamp()
        );
    }

    private Instant requireCreatedAt(FraudFeedbackRecord source) {
        if (source.getCreatedAt() == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        return source.getCreatedAt();
    }
}
