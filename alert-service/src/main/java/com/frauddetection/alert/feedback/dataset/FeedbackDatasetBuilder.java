package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.feedback.governance.FeedbackDatasetEligibility;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class FeedbackDatasetBuilder {

    public static final String DATASET_VERSION = "feedback-dataset-v2";

    private static final Logger log = LoggerFactory.getLogger(FeedbackDatasetBuilder.class);

    private final FeedbackDatasetCandidateStore candidateStore;
    private final FeedbackDatasetMappingPolicy mappingPolicy;
    private final MlPredictionEvidenceProjectionRepository evidenceRepository;
    private final FeedbackDatasetMetricsRecorder metricsRecorder;
    private final Clock clock;

    @Autowired
    public FeedbackDatasetBuilder(
            FeedbackDatasetCandidateStore candidateStore,
            FeedbackDatasetMappingPolicy mappingPolicy,
            MlPredictionEvidenceProjectionRepository evidenceRepository,
            FeedbackDatasetMetricsRecorder metricsRecorder
    ) {
        this(candidateStore, mappingPolicy, evidenceRepository, metricsRecorder, Clock.systemUTC());
    }

    FeedbackDatasetBuilder(
            FeedbackDatasetCandidateStore candidateStore,
            FeedbackDatasetMappingPolicy mappingPolicy,
            MlPredictionEvidenceProjectionRepository evidenceRepository,
            Clock clock
    ) {
        this(candidateStore, mappingPolicy, evidenceRepository, FeedbackDatasetMetricsRecorder.noOp(), clock);
    }

    FeedbackDatasetBuilder(
            FeedbackDatasetCandidateStore candidateStore,
            FeedbackDatasetMappingPolicy mappingPolicy,
            MlPredictionEvidenceProjectionRepository evidenceRepository,
            FeedbackDatasetMetricsRecorder metricsRecorder,
            Clock clock
    ) {
        this.candidateStore = Objects.requireNonNull(candidateStore, "candidateStore is required");
        this.mappingPolicy = Objects.requireNonNull(mappingPolicy, "mappingPolicy is required");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository is required");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public FeedbackDatasetBuildResult build(FeedbackDatasetBuildRequest request) {
        Instant builtAt = Instant.now(clock);
        if (!valid(request)) {
            return finish(FeedbackDatasetBuildResult.failed(
                    request,
                    builtAt,
                    FeedbackDatasetBuildFailureReason.INVALID_REQUEST
            ));
        }
        int maxRecords = request.effectiveMaxRecords();
        List<FraudFeedbackRecord> rawRows;
        try {
            rawRows = candidateStore.findBoundedByCreatedAt(request.fromInclusive(), request.toInclusive(), maxRecords);
        } catch (RuntimeException exception) {
            log.warn("Feedback dataset candidate lookup failed. reason=FEEDBACK_STORE_UNAVAILABLE");
            return finish(FeedbackDatasetBuildResult.failed(
                    request,
                    builtAt,
                    FeedbackDatasetBuildFailureReason.FEEDBACK_STORE_UNAVAILABLE
            ));
        }

        boolean truncated = rawRows.size() > maxRecords;
        List<FraudFeedbackRecord> boundedRows = rawRows.stream().limit(maxRecords).toList();
        int excludedUnresolved = 0;
        int excludedGovernanceReview = 0;
        int skippedMissingRequired = 0;
        int skippedInvalidSource = 0;
        List<FeedbackDatasetRecord> records = new ArrayList<>();
        List<EligibleSource> eligibleSources = new ArrayList<>();

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
            Optional<FeedbackEvaluationLabel> evaluationLabel = mappingPolicy.evaluationLabel(source.getFeedbackLabel());
            if (evaluationLabel.isEmpty()) {
                excludedGovernanceReview++;
                continue;
            }
            Optional<ScoringOccurrenceOwnership> ownership;
            try {
                ownership = source.scoringOccurrenceOwnership();
            } catch (IllegalStateException exception) {
                skippedInvalidSource++;
                continue;
            }
            if (ownership.isEmpty()) {
                skippedMissingRequired++;
                continue;
            }
            eligibleSources.add(new EligibleSource(
                    source,
                    evaluationLabel.orElseThrow(),
                    ownership.orElseThrow()
            ));
        }

        List<ResolvedSource> resolvedSources;
        try {
            resolvedSources = resolveMlPredictionEvidence(eligibleSources);
        } catch (DataAccessException exception) {
            log.warn("ML prediction evidence dataset lookup failed. reason=STORE_UNAVAILABLE");
            return finish(FeedbackDatasetBuildResult.failed(
                    request,
                    builtAt,
                    FeedbackDatasetBuildFailureReason.ML_PREDICTION_EVIDENCE_STORE_UNAVAILABLE
            ));
        } catch (RuntimeException exception) {
            log.warn("ML prediction evidence dataset lookup failed. reason=INTEGRITY_FAILURE");
            return finish(FeedbackDatasetBuildResult.failed(
                    request,
                    builtAt,
                    FeedbackDatasetBuildFailureReason.ML_PREDICTION_EVIDENCE_INTEGRITY_FAILURE
            ));
        }

        for (ResolvedSource resolved : resolvedSources) {
            try {
                FraudFeedbackRecord source = resolved.source().source();
                records.add(record(
                        source,
                        resolved.source().evaluationLabel(),
                        validatedDecisionReasonCodes(source),
                        resolved.evidence()
                ));
            } catch (MissingRequiredSourceFieldException exception) {
                skippedMissingRequired++;
            } catch (IllegalArgumentException exception) {
                skippedInvalidSource++;
            }
        }

        return finish(FeedbackDatasetBuildResult.succeeded(
                request,
                builtAt,
                rawRows.size(),
                records.size(),
                excludedUnresolved,
                excludedGovernanceReview,
                skippedMissingRequired,
                skippedInvalidSource,
                truncated,
                records
        ));
    }

    private FeedbackDatasetBuildResult finish(FeedbackDatasetBuildResult result) {
        metricsRecorder.record(result);
        return result;
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

    private FeedbackDatasetRecord record(
            FraudFeedbackRecord source,
            FeedbackEvaluationLabel evaluationLabel,
            List<String> validatedDecisionReasonCodes,
            FeedbackDatasetMlPredictionEvidence evidence
    ) {
        MlPredictionEvidenceProjection projection = evidence.projection().orElse(null);
        return new FeedbackDatasetRecord(
                DATASET_VERSION,
                FeedbackDatasetIdentifierHasher.evaluationRecordId(requireSourceText(source.getFeedbackId(), "feedbackId")),
                FeedbackDatasetIdentifierHasher.transactionReference(requireSourceText(source.getTransactionId(), "transactionId")),
                source.getFeedbackLabel(),
                evaluationLabel,
                validatedDecisionReasonCodes,
                requireCreatedAt(source),
                source.getFraudScore(),
                source.getRiskLevel(),
                source.getAlertRecommended(),
                source.getEngineIntelligenceStatus(),
                source.getAgreementStatus(),
                source.getRiskMismatchStatus(),
                source.getScoreDeltaBucket(),
                rulesEvidenceStatus(source),
                source.getRulesEngineStatus() == FraudEngineStatus.AVAILABLE
                        ? source.getRulesRiskLevel()
                        : null,
                evidence.status(),
                evidence.omissionReason().orElse(null),
                projection == null ? null : projection.getMlScore(),
                projection == null ? null : projection.getMlRiskLevel(),
                projection == null ? null : projection.getSourceExecutionTimestamp(),
                projection == null ? null : projection.getModelName(),
                projection == null ? null : projection.getModelVersion(),
                projection == null ? null : projection.getFeatureContractVersion(),
                source.getAnalystRecommendationStatus(),
                source.getAnalystRecommendation(),
                source.getAnalystRecommendationVersion(),
                source.getAnalystRecommendationGeneratedAt(),
                source.getAnalystRecommendationReasonCodes(),
                source.getScoredAt(),
                source.getTransactionTimestamp()
        );
    }

    private FeedbackDatasetRulesEvidenceStatus rulesEvidenceStatus(FraudFeedbackRecord source) {
        if (source.getRulesEngineStatus() == FraudEngineStatus.AVAILABLE) {
            if (source.getRulesRiskLevel() == null) {
                throw new IllegalArgumentException("available Rules evidence requires rulesRiskLevel");
            }
            return FeedbackDatasetRulesEvidenceStatus.AVAILABLE;
        }
        if (source.getRulesRiskLevel() != null) {
            throw new IllegalArgumentException("unavailable Rules evidence must not carry rulesRiskLevel");
        }
        return FeedbackDatasetRulesEvidenceStatus.UNAVAILABLE;
    }

    private List<ResolvedSource> resolveMlPredictionEvidence(List<EligibleSource> eligibleSources) {
        Set<String> sourceEventIds = new LinkedHashSet<>();
        for (EligibleSource eligible : eligibleSources) {
            sourceEventIds.add(eligible.ownership().sourceEventId());
        }

        Map<String, MlPredictionEvidenceProjection> projectionBySourceEventId = new LinkedHashMap<>();
        if (!sourceEventIds.isEmpty()) {
            for (MlPredictionEvidenceProjection projection : evidenceRepository.findAllById(sourceEventIds)) {
                if (projection == null || !sourceEventIds.contains(projection.getSourceEventId())) {
                    throw new IllegalStateException("ML prediction evidence lookup returned an invalid projection");
                }
                if (projectionBySourceEventId.putIfAbsent(projection.getSourceEventId(), projection) != null) {
                    throw new IllegalStateException("ML prediction evidence lookup returned duplicate projections");
                }
            }
        }

        List<ResolvedSource> resolved = new ArrayList<>(eligibleSources.size());
        for (EligibleSource eligible : eligibleSources) {
            MlPredictionEvidenceProjection projection = projectionBySourceEventId.get(
                    eligible.ownership().sourceEventId()
            );
            resolved.add(new ResolvedSource(eligible, classifyEvidence(
                    eligible.source(),
                    eligible.ownership(),
                    projection
            )));
        }
        return resolved;
    }

    private FeedbackDatasetMlPredictionEvidence classifyEvidence(
            FraudFeedbackRecord source,
            ScoringOccurrenceOwnership ownership,
            MlPredictionEvidenceProjection projection
    ) {
        int feedbackIdentityParts = presentIdentityParts(source);
        if (feedbackIdentityParts != 0 && feedbackIdentityParts != 3) {
            return FeedbackDatasetMlPredictionEvidence.unavailable(
                    FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED
            );
        }
        if (projection == null) {
            MlPredictionEvidenceOmissionReason omissionReason = source.getMlPredictionEvidenceOmissionReason();
            return omissionReason == null
                    ? FeedbackDatasetMlPredictionEvidence.unavailable(
                            FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY
                    )
                    : FeedbackDatasetMlPredictionEvidence.omitted(omissionReason);
        }
        if (source.getMlPredictionEvidenceOmissionReason() != null) {
            return FeedbackDatasetMlPredictionEvidence.unavailable(
                    FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED
            );
        }

        try {
            new MlPredictionEvidenceV1(
                    projection.getContractVersion(),
                    projection.getSourceEngineId(),
                    projection.getEngineStatus(),
                    projection.getMlScore(),
                    projection.getMlRiskLevel(),
                    projection.getModelName(),
                    projection.getModelVersion(),
                    projection.getFeatureContractVersion(),
                    projection.getSourceExecutionTimestamp()
            );
            if (!ownership.sourceEventId().equals(projection.getSourceEventId())
                    || !ownership.sourceEventCreatedAt().equals(projection.getSourceEventCreatedAt())
                    || !Objects.equals(source.getTransactionId(), projection.getTransactionId())
                    || (source.getCorrelationId() != null
                    && !Objects.equals(source.getCorrelationId(), projection.getCorrelationId()))) {
                return FeedbackDatasetMlPredictionEvidence.unavailable(
                        FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH
                );
            }
            if (feedbackIdentityParts == 3
                    && (!Objects.equals(source.getMlModelName(), projection.getModelName())
                    || !Objects.equals(source.getMlModelVersion(), projection.getModelVersion())
                    || !Objects.equals(
                            source.getMlFeatureContractVersion(),
                            projection.getFeatureContractVersion()
                    ))) {
                return FeedbackDatasetMlPredictionEvidence.unavailable(
                        FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH
                );
            }
            return FeedbackDatasetMlPredictionEvidence.available(projection);
        } catch (IllegalArgumentException exception) {
            return FeedbackDatasetMlPredictionEvidence.unavailable(
                    FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED
            );
        }
    }

    private int presentIdentityParts(FraudFeedbackRecord source) {
        int present = 0;
        present += source.getMlModelName() == null ? 0 : 1;
        present += source.getMlModelVersion() == null ? 0 : 1;
        present += source.getMlFeatureContractVersion() == null ? 0 : 1;
        return present;
    }

    private List<String> validatedDecisionReasonCodes(FraudFeedbackRecord source) {
        if (source.getDecisionReasonCodes() == null || source.getDecisionReasonCodes().isEmpty()) {
            throw new MissingRequiredSourceFieldException();
        }
        return FeedbackDatasetReasonCodePolicy.validatedDecisionReasonCodes(
                source.getFeedbackLabel(),
                source.getDecisionReasonCodes()
        );
    }

    private Instant requireCreatedAt(FraudFeedbackRecord source) {
        if (source.getCreatedAt() == null) {
            throw new MissingRequiredSourceFieldException();
        }
        return source.getCreatedAt();
    }

    private String requireSourceText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new MissingRequiredSourceFieldException();
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " contains control characters");
        }
        return value;
    }

    private static class MissingRequiredSourceFieldException extends RuntimeException {
    }

    private record EligibleSource(
            FraudFeedbackRecord source,
            FeedbackEvaluationLabel evaluationLabel,
            ScoringOccurrenceOwnership ownership
    ) {
    }

    private record ResolvedSource(
            EligibleSource source,
            FeedbackDatasetMlPredictionEvidence evidence
    ) {
    }

}
