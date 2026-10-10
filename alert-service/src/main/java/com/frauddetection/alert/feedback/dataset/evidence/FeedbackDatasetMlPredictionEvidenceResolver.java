package com.frauddetection.alert.feedback.dataset.evidence;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.feedback.dataset.FeedbackDatasetMlPredictionEvidenceResolutionProvenance;
import com.frauddetection.alert.feedback.dataset.FeedbackDatasetMlPredictionEvidenceStatus;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class FeedbackDatasetMlPredictionEvidenceResolver {

    private final MlPredictionEvidenceProjectionRepository repository;

    public FeedbackDatasetMlPredictionEvidenceResolver(MlPredictionEvidenceProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
    }

    public List<Resolution> resolve(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates are required");
        Set<String> sourceEventIds = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            sourceEventIds.add(candidate.ownership().sourceEventId());
        }

        Map<String, MlPredictionEvidenceProjection> projectionBySourceEventId = loadBatch(sourceEventIds);
        List<Resolution> resolutions = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            resolutions.add(classify(
                    candidate.source(),
                    candidate.ownership(),
                    projectionBySourceEventId.get(candidate.ownership().sourceEventId())
            ));
        }
        return List.copyOf(resolutions);
    }

    private Map<String, MlPredictionEvidenceProjection> loadBatch(Set<String> sourceEventIds) {
        Map<String, MlPredictionEvidenceProjection> projectionBySourceEventId = new LinkedHashMap<>();
        if (sourceEventIds.isEmpty()) {
            return projectionBySourceEventId;
        }
        for (MlPredictionEvidenceProjection projection : repository.findAllById(sourceEventIds)) {
            if (projection == null || !sourceEventIds.contains(projection.getSourceEventId())) {
                throw new IllegalStateException("ML prediction evidence lookup returned an invalid projection");
            }
            if (projectionBySourceEventId.putIfAbsent(projection.getSourceEventId(), projection) != null) {
                throw new IllegalStateException("ML prediction evidence lookup returned duplicate projections");
            }
        }
        return projectionBySourceEventId;
    }

    private Resolution classify(
            FraudFeedbackRecord source,
            ScoringOccurrenceOwnership ownership,
            MlPredictionEvidenceProjection projection
    ) {
        int feedbackLineageParts = presentLineageParts(source);
        MlPredictionEvidenceOmissionReason omissionReason = source.getMlPredictionEvidenceOmissionReason();
        if (omissionReason != null && feedbackLineageParts != 0) {
            return Resolution.unavailable(FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED);
        }
        if (feedbackLineageParts != 0 && feedbackLineageParts != 4) {
            return Resolution.unavailable(FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED);
        }
        if (projection == null) {
            return omissionReason == null
                    ? Resolution.unavailable(FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY)
                    : Resolution.omitted(omissionReason);
        }
        try {
            if (!ownership.sourceEventId().equals(projection.getSourceEventId())
                    || !ownership.sourceEventCreatedAt().equals(projection.getSourceEventCreatedAt())
                    || !Objects.equals(source.getTransactionId(), projection.getTransactionId())
                    || (source.getCorrelationId() != null
                    && !Objects.equals(source.getCorrelationId(), projection.getCorrelationId()))) {
                return Resolution.unavailable(FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH);
            }
            if (!projection.hasEvidence()) {
                if (feedbackLineageParts != 0
                        || omissionReason == null
                        || omissionReason != projection.getMlPredictionEvidenceOmissionReason()) {
                    return Resolution.unavailable(FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED);
                }
                return Resolution.omitted(projection.getMlPredictionEvidenceOmissionReason());
            }
            if (omissionReason != null) {
                return Resolution.unavailable(FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED);
            }
            if (feedbackLineageParts != 0
                    && (!Objects.equals(source.getMlModelName(), projection.getModelName())
                    || !Objects.equals(source.getMlModelVersion(), projection.getModelVersion())
                    || !Objects.equals(
                            source.getMlFeatureContractVersion(),
                            projection.getFeatureContractVersion()
                    )
                    || !Objects.equals(source.getMlModelArtifactSha256(), projection.getModelArtifactSha256()))) {
                return Resolution.unavailable(FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH);
            }
            FeedbackDatasetMlPredictionEvidenceResolutionProvenance resolutionProvenance =
                    feedbackLineageParts == 4
                            ? FeedbackDatasetMlPredictionEvidenceResolutionProvenance.CAPTURED_AND_CONFIRMED
                            : FeedbackDatasetMlPredictionEvidenceResolutionProvenance
                                    .RECOVERED_FROM_EXACT_OCCURRENCE_PROJECTION;
            return Resolution.available(projection, resolutionProvenance);
        } catch (IllegalArgumentException exception) {
            return Resolution.unavailable(FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED);
        }
    }

    private int presentLineageParts(FraudFeedbackRecord source) {
        int present = 0;
        present += source.getMlModelName() == null ? 0 : 1;
        present += source.getMlModelVersion() == null ? 0 : 1;
        present += source.getMlFeatureContractVersion() == null ? 0 : 1;
        present += source.getMlModelArtifactSha256() == null ? 0 : 1;
        return present;
    }

    public record Candidate(
            FraudFeedbackRecord source,
            ScoringOccurrenceOwnership ownership
    ) {
        public Candidate {
            source = Objects.requireNonNull(source, "source is required");
            ownership = Objects.requireNonNull(ownership, "ownership is required");
        }
    }

    public record Resolution(
            FeedbackDatasetMlPredictionEvidenceStatus status,
            Optional<FeedbackDatasetMlPredictionEvidenceResolutionProvenance> resolutionProvenance,
            Optional<MlPredictionEvidenceProjection> projection,
            Optional<MlPredictionEvidenceOmissionReason> omissionReason
    ) {
        public Resolution {
            status = Objects.requireNonNull(status, "status is required");
            resolutionProvenance = Objects.requireNonNull(resolutionProvenance, "resolutionProvenance is required");
            projection = Objects.requireNonNull(projection, "projection is required");
            omissionReason = Objects.requireNonNull(omissionReason, "omissionReason is required");
            if ((status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE) != projection.isPresent()) {
                throw new IllegalArgumentException("only available ML prediction evidence may carry a projection");
            }
            if (projection.isPresent() != resolutionProvenance.isPresent()) {
                throw new IllegalArgumentException("available ML prediction evidence requires resolution provenance");
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

        private static Resolution available(
                MlPredictionEvidenceProjection projection,
                FeedbackDatasetMlPredictionEvidenceResolutionProvenance resolutionProvenance
        ) {
            return new Resolution(
                    FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE,
                    Optional.of(Objects.requireNonNull(resolutionProvenance, "resolutionProvenance is required")),
                    Optional.of(Objects.requireNonNull(projection, "projection is required")),
                    Optional.empty()
            );
        }

        private static Resolution omitted(MlPredictionEvidenceOmissionReason reason) {
            MlPredictionEvidenceOmissionReason required = Objects.requireNonNull(reason, "reason is required");
            return new Resolution(
                    FeedbackDatasetMlPredictionEvidenceStatus.fromAuthoritativeOmission(required),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(required)
            );
        }

        private static Resolution unavailable(FeedbackDatasetMlPredictionEvidenceStatus status) {
            if (status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE) {
                throw new IllegalArgumentException("available evidence requires a projection");
            }
            return new Resolution(status, Optional.empty(), Optional.empty(), Optional.empty());
        }
    }
}
