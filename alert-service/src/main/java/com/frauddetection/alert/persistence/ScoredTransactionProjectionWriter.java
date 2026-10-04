package com.frauddetection.alert.persistence;

import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEW;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEWER;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.CONFLICT_REJECTED;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.IDEMPOTENT_REPLAY;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.STALE_REJECTED;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.ReasonCode.FIRST_OCCURRENCE_ACCEPTED;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.ReasonCode.HISTORICAL_OCCURRENCE_CLAIMED;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.ReasonCode.IDENTICAL_OCCURRENCE_REPLAYED;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.ReasonCode.NEWER_OCCURRENCE_ACCEPTED;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.ReasonCode.OCCURRENCE_FINGERPRINT_MISSING;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.ReasonCode.OCCURRENCE_PAYLOAD_CONFLICT;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.ReasonCode.OLDER_OCCURRENCE_REJECTED;

@Component
public class ScoredTransactionProjectionWriter {

    private static final int MAX_ADMISSION_ATTEMPTS = 8;

    private final MongoTemplate mongoTemplate;

    public ScoredTransactionProjectionWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public ScoringOccurrenceAdmissionResult write(ScoredTransactionDocument candidate) {
        validate(candidate);
        for (int attempt = 0; attempt < MAX_ADMISSION_ATTEMPTS; attempt++) {
            ScoredTransactionDocument current = mongoTemplate.findById(
                    candidate.getTransactionId(),
                    ScoredTransactionDocument.class
            );
            if (current != null) {
                if (isHistoricalUnknown(current)) {
                    if (updated(replaceHistoricalUnknown(candidate))) {
                        return new ScoringOccurrenceAdmissionResult(APPLIED_NEWER, HISTORICAL_OCCURRENCE_CLAIMED);
                    }
                    continue;
                }
                ScoringOccurrenceAdmissionResult classified = classify(candidate, current);
                if (classified != null) {
                    return classified;
                }
                if (updated(replaceOlder(candidate))) {
                    return new ScoringOccurrenceAdmissionResult(APPLIED_NEWER, NEWER_OCCURRENCE_ACCEPTED);
                }
                continue;
            }
            try {
                mongoTemplate.insert(candidate);
                return new ScoringOccurrenceAdmissionResult(APPLIED_NEW, FIRST_OCCURRENCE_ACCEPTED);
            } catch (DuplicateKeyException concurrentInsertOrReplay) {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw concurrentInsertOrReplay;
                }
            }
        }
        throw new IllegalStateException("SCORING_OCCURRENCE_ADMISSION_INDETERMINATE");
    }

    private UpdateResult replaceHistoricalUnknown(ScoredTransactionDocument candidate) {
        return mongoTemplate.updateFirst(
                historicalUnknownQuery(candidate),
                replacementUpdate(candidate),
                ScoredTransactionDocument.class
        );
    }

    private UpdateResult replaceOlder(ScoredTransactionDocument candidate) {
        return mongoTemplate.updateFirst(
                olderOccurrenceQuery(candidate),
                replacementUpdate(candidate),
                ScoredTransactionDocument.class
        );
    }

    private Query historicalUnknownQuery(ScoredTransactionDocument candidate) {
        Criteria unknownOccurrence = new Criteria().andOperator(
                Criteria.where("sourceEventId").is(null),
                Criteria.where("sourceEventCreatedAt").is(null),
                Criteria.where("sourceEventCreatedAtEpochSecond").is(null),
                Criteria.where("sourceEventCreatedAtNano").is(null),
                Criteria.where("sourceEventFingerprint").is(null)
        );
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(candidate.getTransactionId()),
                unknownOccurrence
        ));
    }

    private Query olderOccurrenceQuery(ScoredTransactionDocument candidate) {
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
                laterOccurrence
        ));
    }

    private boolean updated(UpdateResult result) {
        if (!result.wasAcknowledged()
                || result.getMatchedCount() > 1
                || result.getModifiedCount() > 1
                || result.getMatchedCount() != result.getModifiedCount()) {
            throw new IllegalStateException("SCORING_OCCURRENCE_WRITE_RESULT_INVALID");
        }
        return result.getModifiedCount() == 1;
    }

    private ScoringOccurrenceAdmissionResult classify(
            ScoredTransactionDocument candidate,
            ScoredTransactionDocument current
    ) {
        if (current == null) {
            return null;
        }
        if (candidate.getSourceEventId().equals(current.getSourceEventId())) {
            if (current.getSourceEventFingerprint() == null) {
                return new ScoringOccurrenceAdmissionResult(CONFLICT_REJECTED, OCCURRENCE_FINGERPRINT_MISSING);
            }
            if (candidate.getSourceEventFingerprint().equals(current.getSourceEventFingerprint())) {
                return new ScoringOccurrenceAdmissionResult(IDEMPOTENT_REPLAY, IDENTICAL_OCCURRENCE_REPLAYED);
            }
            return new ScoringOccurrenceAdmissionResult(CONFLICT_REJECTED, OCCURRENCE_PAYLOAD_CONFLICT);
        }
        int ordering = compareOccurrence(candidate, current);
        if (ordering < 0) {
            return new ScoringOccurrenceAdmissionResult(STALE_REJECTED, OLDER_OCCURRENCE_REJECTED);
        }
        if (ordering == 0) {
            throw new IllegalStateException("SCORING_OCCURRENCE_ORDERING_INVALID");
        }
        return null;
    }

    private boolean isHistoricalUnknown(ScoredTransactionDocument current) {
        return current.getSourceEventId() == null
                && current.getSourceEventCreatedAt() == null
                && current.getSourceEventCreatedAtEpochSecond() == null
                && current.getSourceEventCreatedAtNano() == null
                && current.getSourceEventFingerprint() == null;
    }

    private int compareOccurrence(ScoredTransactionDocument candidate, ScoredTransactionDocument current) {
        if (current.getSourceEventCreatedAtEpochSecond() == null
                || current.getSourceEventCreatedAtNano() == null
                || current.getSourceEventId() == null) {
            throw new IllegalStateException("SCORING_OCCURRENCE_IDENTITY_INVALID");
        }
        int seconds = Long.compare(
                candidate.getSourceEventCreatedAtEpochSecond(),
                current.getSourceEventCreatedAtEpochSecond()
        );
        if (seconds != 0) {
            return seconds;
        }
        int nanos = Integer.compare(candidate.getSourceEventCreatedAtNano(), current.getSourceEventCreatedAtNano());
        if (nanos != 0) {
            return nanos;
        }
        return candidate.getSourceEventId().compareTo(current.getSourceEventId());
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
                || candidate.getSourceEventCreatedAtNano() == null
                || candidate.getSourceEventFingerprint() == null
                || !candidate.getSourceEventFingerprint().matches("[0-9a-f]{64}")) {
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
