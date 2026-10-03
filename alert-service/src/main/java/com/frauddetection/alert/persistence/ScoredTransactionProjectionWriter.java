package com.frauddetection.alert.persistence;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ScoredTransactionProjectionWriter {

    private final MongoTemplate mongoTemplate;

    public ScoredTransactionProjectionWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void write(ScoredTransactionDocument candidate) {
        validate(candidate);
        if (replaceOlderOrUnknown(candidate).getMatchedCount() == 1) {
            return;
        }
        try {
            mongoTemplate.insert(candidate);
        } catch (DuplicateKeyException concurrentInsertOrReplay) {
            replaceOlderOrUnknown(candidate);
        }
    }

    private UpdateResult replaceOlderOrUnknown(ScoredTransactionDocument candidate) {
        return mongoTemplate.updateFirst(
                authoritativeReplacementQuery(candidate),
                replacementUpdate(candidate),
                ScoredTransactionDocument.class
        );
    }

    private Query authoritativeReplacementQuery(ScoredTransactionDocument candidate) {
        Criteria unknownOccurrence = new Criteria().orOperator(
                Criteria.where("sourceEventId").is(null),
                Criteria.where("sourceEventCreatedAt").is(null),
                Criteria.where("sourceEventCreatedAtEpochSecond").is(null),
                Criteria.where("sourceEventCreatedAtNano").is(null)
        );
        Criteria laterOccurrence = new Criteria().andOperator(
                Criteria.where("sourceEventId").ne(candidate.getSourceEventId()),
                new Criteria().orOperator(
                        Criteria.where("sourceEventCreatedAtEpochSecond")
                                .lt(candidate.getSourceEventCreatedAtEpochSecond()),
                        new Criteria().andOperator(
                                Criteria.where("sourceEventCreatedAtEpochSecond")
                                        .is(candidate.getSourceEventCreatedAtEpochSecond()),
                                Criteria.where("sourceEventCreatedAtNano")
                                        .lt(candidate.getSourceEventCreatedAtNano())
                        ),
                        new Criteria().andOperator(
                                Criteria.where("sourceEventCreatedAtEpochSecond")
                                        .is(candidate.getSourceEventCreatedAtEpochSecond()),
                                Criteria.where("sourceEventCreatedAtNano")
                                        .is(candidate.getSourceEventCreatedAtNano()),
                                Criteria.where("sourceEventId").lt(candidate.getSourceEventId())
                        )
                )
        );
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(candidate.getTransactionId()),
                new Criteria().orOperator(unknownOccurrence, laterOccurrence)
        ));
    }

    private Update replacementUpdate(ScoredTransactionDocument candidate) {
        Document persisted = new Document();
        mongoTemplate.getConverter().write(candidate, persisted);
        return Update.fromDocument(persisted, "_id");
    }

    private void validate(ScoredTransactionDocument candidate) {
        if (candidate == null
                || candidate.getTransactionId() == null
                || candidate.getTransactionId().isBlank()
                || candidate.getSourceEventId() == null
                || candidate.getSourceEventId().isBlank()
                || candidate.getSourceEventCreatedAt() == null
                || candidate.getSourceEventCreatedAtEpochSecond() == null
                || candidate.getSourceEventCreatedAtNano() == null) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_OCCURRENCE_REQUIRED");
        }
        Instant sourceEventCreatedAt;
        try {
            sourceEventCreatedAt = Instant.parse(candidate.getSourceEventCreatedAt());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_OCCURRENCE_REQUIRED");
        }
        try {
            ScoringOccurrenceOwnership.authoritative(candidate.getSourceEventId(), sourceEventCreatedAt);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_OCCURRENCE_REQUIRED");
        }
        if (sourceEventCreatedAt.getEpochSecond() != candidate.getSourceEventCreatedAtEpochSecond()
                || sourceEventCreatedAt.getNano() != candidate.getSourceEventCreatedAtNano()
                || !sourceEventCreatedAt.toString().equals(candidate.getSourceEventCreatedAt())) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_OCCURRENCE_REQUIRED");
        }
    }
}
