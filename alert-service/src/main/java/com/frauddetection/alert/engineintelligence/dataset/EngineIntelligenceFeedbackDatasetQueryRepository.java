package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Repository
class EngineIntelligenceFeedbackDatasetQueryRepository {

    private final MongoTemplate mongoTemplate;

    EngineIntelligenceFeedbackDatasetQueryRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
    }

    /**
     * Fetches at most {@code maxRecords + 1} raw feedback rows so callers can detect truncation without scanning the
     * full window.
     */
    List<EngineIntelligenceFeedbackDocument> findBoundedBySubmittedAt(
            Instant fromInclusive,
            Instant toInclusive,
            int maxRecords
    ) {
        Query query = Query.query(Criteria.where("submittedAt").gte(fromInclusive).lte(toInclusive))
                .with(Sort.by(
                        Sort.Order.desc("submittedAt"),
                        Sort.Order.asc("feedbackId")
                ))
                .limit(maxRecords + 1);
        return mongoTemplate.find(query, EngineIntelligenceFeedbackDocument.class);
    }
}
