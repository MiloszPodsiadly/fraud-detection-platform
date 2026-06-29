package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Repository
class FeedbackDatasetCandidateStore {

    private static final List<FraudFeedbackLabel> DATASET_CANDIDATE_LABELS = List.of(
            FraudFeedbackLabel.CONFIRMED_FRAUD,
            FraudFeedbackLabel.CONFIRMED_LEGITIMATE,
            FraudFeedbackLabel.INCONCLUSIVE,
            FraudFeedbackLabel.NEEDS_MORE_INFO
    );

    private final MongoTemplate mongoTemplate;

    FeedbackDatasetCandidateStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
    }

    List<FraudFeedbackRecord> findBoundedByCreatedAt(
            Instant fromInclusive,
            Instant toInclusive,
            int maxRecords
    ) {
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("feedbackLabel").in(DATASET_CANDIDATE_LABELS),
                        Criteria.where("createdAt").gte(fromInclusive).lte(toInclusive)
                ))
                .with(Sort.by(
                        Sort.Order.asc("createdAt"),
                        Sort.Order.asc("feedbackId")
                ))
                .limit(maxRecords + 1);
        return mongoTemplate.find(query, FraudFeedbackRecord.class);
    }
}
