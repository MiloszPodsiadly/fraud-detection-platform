package com.frauddetection.alert.engineintelligence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
            EngineIntelligenceProjection updated = mongoTemplate.findAndModify(
                    replaceableProjection(candidate),
                    update(candidate),
                    FindAndModifyOptions.options().returnNew(true),
                    EngineIntelligenceProjection.class
            );
            if (updated != null) {
                return accepted(updated);
            }
            EngineIntelligenceProjection current = mongoTemplate.findById(
                    candidate.getTransactionId(),
                    EngineIntelligenceProjection.class
            );
            if (current != null) {
                if (isOlder(current, candidate)) {
                    continue;
                }
                return classifyExisting(current, candidate);
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

    private EngineIntelligenceProjectionWriteResult classifyExisting(
            EngineIntelligenceProjection current,
            EngineIntelligenceProjection candidate
    ) {
        if (sameSourceEvent(current, candidate)) {
            if (!Objects.equals(current.getSourceEventFingerprint(), candidate.getSourceEventFingerprint())) {
                return new EngineIntelligenceProjectionWriteResult(
                        EngineIntelligenceProjectionWriteResult.Status.SOURCE_PAYLOAD_CONFLICT,
                        current
                );
            }
            return accepted(current);
        }
        return new EngineIntelligenceProjectionWriteResult(
                EngineIntelligenceProjectionWriteResult.Status.STALE,
                current
        );
    }

    private Query replaceableProjection(EngineIntelligenceProjection candidate) {
        Criteria sameOccurrence = new Criteria().andOperator(
                Criteria.where("sourceEventId").is(candidate.getSourceEventId()),
                new Criteria().orOperator(
                        Criteria.where("sourceEventFingerprint").is(candidate.getSourceEventFingerprint()),
                        Criteria.where("sourceEventFingerprint").exists(false),
                        Criteria.where("sourceEventFingerprint").is(null)
                )
        );
        Criteria olderOccurrence = new Criteria().orOperator(
                Criteria.where("sourceEventCreatedAt").lt(candidate.getSourceEventCreatedAt()),
                new Criteria().andOperator(
                        Criteria.where("sourceEventCreatedAt").is(candidate.getSourceEventCreatedAt()),
                        Criteria.where("sourceEventId").lt(candidate.getSourceEventId())
                )
        );
        Criteria historicalWithoutFence = new Criteria().orOperator(
                Criteria.where("sourceEventCreatedAt").exists(false),
                Criteria.where("sourceEventCreatedAt").is(null),
                Criteria.where("sourceEventId").exists(false),
                Criteria.where("sourceEventId").is(null)
        );
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(candidate.getTransactionId()),
                new Criteria().orOperator(sameOccurrence, olderOccurrence, historicalWithoutFence)
        ));
    }

    private Update update(EngineIntelligenceProjection candidate) {
        return new Update()
                .set("sourceEventId", candidate.getSourceEventId())
                .set("sourceEventCreatedAt", candidate.getSourceEventCreatedAt())
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
        if (current.getSourceEventCreatedAt() == null || current.getSourceEventId() == null) {
            return true;
        }
        int timestampOrder = current.getSourceEventCreatedAt().compareTo(candidate.getSourceEventCreatedAt());
        return timestampOrder < 0
                || (timestampOrder == 0 && current.getSourceEventId().compareTo(candidate.getSourceEventId()) < 0);
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
        if (candidate == null
                || candidate.getTransactionId() == null
                || candidate.getSourceEventId() == null
                || candidate.getSourceEventCreatedAt() == null
                || candidate.getSourceEventFingerprint() == null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_PROJECTION_OCCURRENCE_IDENTITY_REQUIRED");
        }
    }
}
