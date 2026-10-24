package com.frauddetection.alert.governance.modelpromotion;

import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class ModelPromotionPersistenceIndexInitializer {

    private final MongoTemplate mongoTemplate;

    public ModelPromotionPersistenceIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    void ensureIndexes() {
        // The persistence flag controls the extra startup verification, not Mongo persistence.
        // Required indexes must exist even when that verification is disabled.
        mongoTemplate.indexOps(ModelPromotionPolicyDocument.class).ensureIndex(
                new Index()
                        .on("institutional_scope", Sort.Direction.ASC)
                        .on("environment", Sort.Direction.ASC)
                        .on("intended_use", Sort.Direction.ASC)
                        .on("policy_id", Sort.Direction.ASC)
                        .on("policy_revision", Sort.Direction.ASC)
                        .unique()
                        .named(ModelPromotionPolicyDocument.REVISION_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionPolicyDocument.class).ensureIndex(
                new Index()
                        .on("institutional_scope", Sort.Direction.ASC)
                        .on("environment", Sort.Direction.ASC)
                        .on("intended_use", Sort.Direction.ASC)
                        .on("effective_from", Sort.Direction.DESC)
                        .on("effective_to", Sort.Direction.ASC)
                        .named(ModelPromotionPolicyDocument.EFFECTIVE_LOOKUP_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionStateDocument.class).ensureIndex(
                new Index()
                        .on("model_name", Sort.Direction.ASC)
                        .on("model_version", Sort.Direction.ASC)
                        .on("feature_contract_version", Sort.Direction.ASC)
                        .on("model_artifact_sha256", Sort.Direction.ASC)
                        .on("institutional_scope", Sort.Direction.ASC)
                        .on("environment", Sort.Direction.ASC)
                        .on("intended_use", Sort.Direction.ASC)
                        .unique()
                        .named(ModelPromotionStateDocument.IDENTITY_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC).on("operation_id", Sort.Direction.ASC)
                        .unique().named(ModelPromotionDecisionDocument.OPERATION_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).ensureIndex(
                new Index().on("decision_id", Sort.Direction.ASC).unique().sparse()
                        .named(ModelPromotionDecisionDocument.DECISION_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC)
                        .on("committed_at", Sort.Direction.DESC)
                        .on("operation_id", Sort.Direction.ASC)
                        .named(ModelPromotionDecisionDocument.HISTORY_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionProposalDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC).on("operation_id", Sort.Direction.ASC)
                        .unique().named(ModelPromotionProposalDocument.OPERATION_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionProposalDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC)
                        .on("review_status", Sort.Direction.ASC)
                        .on("submitted_at", Sort.Direction.DESC)
                        .named(ModelPromotionProposalDocument.PENDING_REVIEW_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionProposalDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC)
                        .on("submitted_at", Sort.Direction.DESC)
                        .on("_id", Sort.Direction.ASC)
                        .named(ModelPromotionProposalDocument.HISTORY_INDEX)
        );
        if (!mongoTemplate.collectionExists(ModelPromotionChampionClaimDocument.COLLECTION)) {
            mongoTemplate.createCollection(ModelPromotionChampionClaimDocument.COLLECTION);
        }
        if (!mongoTemplate.collectionExists(ModelPromotionOperationClaimDocument.COLLECTION)) {
            mongoTemplate.createCollection(ModelPromotionOperationClaimDocument.COLLECTION);
        }
    }
}
