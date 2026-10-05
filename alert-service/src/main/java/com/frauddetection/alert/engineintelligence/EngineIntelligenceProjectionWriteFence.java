package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class EngineIntelligenceProjectionWriteFence {

    private static final int MAX_WRITE_RACE_RETRIES = 3;

    private final MongoTemplate mongoTemplate;

    public EngineIntelligenceProjectionWriteFence(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public EngineIntelligenceProjectionWriteResult write(EngineIntelligenceProjection candidate) {
        requireOccurrenceIdentity(candidate);
        for (int attempt = 0; attempt < MAX_WRITE_RACE_RETRIES; attempt++) {
            EngineIntelligenceProjection current = mongoTemplate.findById(
                    candidate.getTransactionId(),
                    EngineIntelligenceProjection.class
            );
            if (current != null) {
                ScoringOccurrenceOwnership currentOwnership = persistedOwnership(current);
                if (sameSourceEvent(current, candidate)) {
                    if (!currentOwnership.equals(candidate.scoringOccurrenceOwnership())) {
                        return new EngineIntelligenceProjectionWriteResult(
                                EngineIntelligenceProjectionWriteResult.Status.SOURCE_PAYLOAD_CONFLICT,
                                current
                        );
                    }
                    EngineIntelligenceProjection updated = replace(candidate);
                    if (updated != null) {
                        return accepted(updated);
                    }
                    continue;
                }
                if (!isOlder(current, candidate)) {
                    return new EngineIntelligenceProjectionWriteResult(
                            EngineIntelligenceProjectionWriteResult.Status.STALE,
                            current
                    );
                }
                EngineIntelligenceProjection updated = replace(candidate);
                if (updated != null) {
                    return accepted(updated);
                }
                continue;
            }
            try {
                return accepted(mongoTemplate.insert(candidate));
            } catch (DuplicateKeyException duplicate) {
                // A concurrent insert requires a fresh transaction/snapshot before classification.
                throw duplicate;
            }
        }
        throw new IllegalStateException("ENGINE_INTELLIGENCE_PROJECTION_WRITE_RACE_EXHAUSTED");
    }

    private EngineIntelligenceProjection replace(EngineIntelligenceProjection candidate) {
        return mongoTemplate.findAndModify(
                replaceableProjection(candidate),
                update(candidate),
                FindAndModifyOptions.options().returnNew(true),
                EngineIntelligenceProjection.class
        );
    }

    private ScoringOccurrenceOwnership persistedOwnership(EngineIntelligenceProjection projection) {
        try {
            return projection.scoringOccurrenceOwnership();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "ENGINE_INTELLIGENCE_PROJECTION_OCCURRENCE_IDENTITY_INVALID",
                    exception
            );
        }
    }

    private Query replaceableProjection(EngineIntelligenceProjection candidate) {
        Criteria sameOccurrence = new Criteria().andOperator(
                Criteria.where("sourceEventId").is(candidate.getSourceEventId()),
                Criteria.where("sourceEventCreatedAt").is(candidate.getSourceEventCreatedAtText()),
                Criteria.where("sourceEventCreatedAtEpochSecond")
                        .is(candidate.getSourceEventCreatedAtEpochSecond()),
                Criteria.where("sourceEventCreatedAtNano").is(candidate.getSourceEventCreatedAtNano()),
                Criteria.where("sourceEventFingerprint").is(candidate.getSourceEventFingerprint())
        );
        Criteria olderOccurrence = new Criteria().andOperator(
                Criteria.where("sourceEventId").ne(candidate.getSourceEventId()),
                new Criteria().orOperator(
                        Criteria.where("sourceEventCreatedAtEpochSecond")
                                .lt(candidate.getSourceEventCreatedAtEpochSecond()),
                        new Criteria().andOperator(
                                Criteria.where("sourceEventCreatedAtEpochSecond")
                                        .is(candidate.getSourceEventCreatedAtEpochSecond()),
                                Criteria.where("sourceEventCreatedAtNano").lt(candidate.getSourceEventCreatedAtNano())
                        ),
                        new Criteria().andOperator(
                                Criteria.where("sourceEventCreatedAtEpochSecond")
                                        .is(candidate.getSourceEventCreatedAtEpochSecond()),
                                Criteria.where("sourceEventCreatedAtNano").is(candidate.getSourceEventCreatedAtNano()),
                                Criteria.where("sourceEventId").lt(candidate.getSourceEventId())
                        )
                )
        );
        Criteria historicalWithoutFence = new Criteria().andOperator(
                Criteria.where("sourceEventId").is(null),
                Criteria.where("sourceEventCreatedAt").is(null),
                Criteria.where("sourceEventCreatedAtEpochSecond").is(null),
                Criteria.where("sourceEventCreatedAtNano").is(null),
                Criteria.where("sourceEventFingerprint").is(null)
        );
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(candidate.getTransactionId()),
                new Criteria().orOperator(sameOccurrence, olderOccurrence, historicalWithoutFence)
        ));
    }

    private Update update(EngineIntelligenceProjection candidate) {
        return new Update()
                .set("sourceEventId", candidate.getSourceEventId())
                .set("sourceEventCreatedAt", candidate.getSourceEventCreatedAtText())
                .set("sourceEventCreatedAtEpochSecond", candidate.getSourceEventCreatedAtEpochSecond())
                .set("sourceEventCreatedAtNano", candidate.getSourceEventCreatedAtNano())
                .set("sourceEventFingerprint", candidate.getSourceEventFingerprint())
                .set("contractVersion", candidate.getContractVersion())
                .set("generatedAt", candidate.getGeneratedAt())
                .set("comparisonType", candidate.getComparisonType())
                .set("comparedEngineIds", candidate.getComparedEngineIds())
                .set("comparisonStatus", candidate.getComparisonStatus())
                .set("riskMismatchStatus", candidate.getRiskMismatchStatus())
                .set("scoreDeltaBucket", candidate.getScoreDeltaBucket())
                .set("engineCount", candidate.getEngineCount())
                .set("diagnosticSignalCount", candidate.getDiagnosticSignalCount())
                .set("warningCount", candidate.getWarningCount())
                .set("engines", candidate.getEngines())
                .set("diagnosticSignals", candidate.getDiagnosticSignals())
                .set("warnings", candidate.getWarnings())
                .set("updatedAt", candidate.getUpdatedAt());
    }

    private boolean isOlder(
            EngineIntelligenceProjection current,
            EngineIntelligenceProjection candidate
    ) {
        ScoringOccurrenceOwnership currentOwnership = persistedOwnership(current);
        if (currentOwnership.state() == ScoringOccurrenceOwnership.State.UNKNOWN_OCCURRENCE) {
            return true;
        }
        ScoringOccurrenceOwnership candidateOwnership = candidate.scoringOccurrenceOwnership();
        int timestampOrder = currentOwnership.sourceEventCreatedAt()
                .compareTo(candidateOwnership.sourceEventCreatedAt());
        return timestampOrder < 0
                || (timestampOrder == 0
                && currentOwnership.sourceEventId().compareTo(candidateOwnership.sourceEventId()) < 0);
    }

    private boolean sameSourceEvent(
            EngineIntelligenceProjection current,
            EngineIntelligenceProjection candidate
    ) {
        return Objects.equals(current.getSourceEventId(), candidate.getSourceEventId());
    }

    private EngineIntelligenceProjectionWriteResult accepted(EngineIntelligenceProjection projection) {
        return new EngineIntelligenceProjectionWriteResult(
                EngineIntelligenceProjectionWriteResult.Status.ACCEPTED,
                projection
        );
    }

    private void requireOccurrenceIdentity(EngineIntelligenceProjection candidate) {
        if (candidate == null || candidate.getTransactionId() == null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_PROJECTION_OCCURRENCE_IDENTITY_REQUIRED");
        }
        try {
            if (candidate.scoringOccurrenceOwnership().state()
                    != ScoringOccurrenceOwnership.State.AUTHORITATIVE) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_PROJECTION_OCCURRENCE_IDENTITY_REQUIRED");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "ENGINE_INTELLIGENCE_PROJECTION_OCCURRENCE_IDENTITY_REQUIRED",
                    exception
            );
        }
    }
}
