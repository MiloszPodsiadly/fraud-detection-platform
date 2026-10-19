package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.MongoException;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;

/** The per-scope fence and policy insert participate in one Mongo replica-set transaction. */
final class MongoModelPromotionPolicyPublicationStore {

    private static final String FENCE_COLLECTION = "model_promotion_policy_publication_fences";

    private final MongoTemplate mongoTemplate;
    private final TransactionTemplate transactions;

    MongoModelPromotionPolicyPublicationStore(MongoTemplate mongoTemplate, TransactionTemplate transactions) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate);
        this.transactions = Objects.requireNonNull(transactions);
    }

    ModelPromotionPolicyPublicationReceipt publish(ModelPromotionPolicy policy, Instant publicationTime) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(publicationTime);
        ensureIndexes();
        try {
            ModelPromotionPolicyPublicationReceipt receipt = transactions.execute(status -> publishWithinTransaction(
                    policy, publicationTime));
            return Objects.requireNonNull(receipt, "Mongo publication transaction returned no receipt");
        } catch (PublicationConflictException exception) {
            throw exception;
        } catch (DuplicateKeyException exception) {
            throw new PublicationConflictException("Policy revision or concurrent publication conflicts", exception);
        } catch (DataAccessException | MongoException exception) {
            throw new IllegalStateException("Mongo policy publication did not commit", exception);
        }
    }

    private ModelPromotionPolicyPublicationReceipt publishWithinTransaction(
            ModelPromotionPolicy policy, Instant publicationTime) {
        ModelPromotionPolicyDocument proposed = ModelPromotionPolicyDocument.from(policy, publicationTime);
        ModelPromotionPolicy.Content content = policy.content();
        ModelGovernanceScope scope = content.governanceScope();
        String fenceId = String.join(":", scope.institutionalScope(), scope.environment().name(),
                content.intendedUse().name());
        mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(fenceId)),
                new Update().inc("generation", 1), FindAndModifyOptions.options().upsert(true).returnNew(true),
                Document.class, FENCE_COLLECTION);

        ModelPromotionPolicyDocument existing = mongoTemplate.findById(
                proposed.policyKey(), ModelPromotionPolicyDocument.class);
        if (existing != null) {
            if (!existing.policy().equals(policy)) {
                throw new PublicationConflictException("An immutable policy revision already has different content");
            }
            return receipt(policy, existing.publishedAt() == null ? publicationTime : existing.publishedAt(), true);
        }
        Query overlap = Query.query(Criteria.where("institutional_scope").is(scope.institutionalScope())
                .and("environment").is(scope.environment())
                .and("intended_use").is(content.intendedUse())
                .and("effective_from").lt(proposed.effectiveTo())
                .and("effective_to").gt(proposed.effectiveFrom()));
        if (mongoTemplate.exists(overlap, ModelPromotionPolicyDocument.class)) {
            throw new PublicationConflictException("Policy effective period overlaps a published policy");
        }
        mongoTemplate.insert(proposed);
        return receipt(policy, publicationTime, false);
    }

    private void ensureIndexes() {
        mongoTemplate.indexOps(ModelPromotionPolicyDocument.class).ensureIndex(new Index()
                .on("institutional_scope", Sort.Direction.ASC)
                .on("environment", Sort.Direction.ASC)
                .on("intended_use", Sort.Direction.ASC)
                .on("policy_id", Sort.Direction.ASC)
                .on("policy_revision", Sort.Direction.ASC)
                .unique().named(ModelPromotionPolicyDocument.REVISION_INDEX));
        mongoTemplate.indexOps(ModelPromotionPolicyDocument.class).ensureIndex(new Index()
                .on("institutional_scope", Sort.Direction.ASC)
                .on("environment", Sort.Direction.ASC)
                .on("intended_use", Sort.Direction.ASC)
                .on("effective_from", Sort.Direction.DESC)
                .on("effective_to", Sort.Direction.ASC)
                .named(ModelPromotionPolicyDocument.EFFECTIVE_LOOKUP_INDEX));
    }

    private ModelPromotionPolicyPublicationReceipt receipt(ModelPromotionPolicy policy, Instant publishedAt,
            boolean alreadyPublished) {
        return new ModelPromotionPolicyPublicationReceipt(policy.content().policyId(), policy.content().revision(),
                policy.canonicalSha256(), policy.authorizationEvidence().authorityIdentity(),
                policy.authorizationEvidence().keyId(), publishedAt, alreadyPublished);
    }

    static final class PublicationConflictException extends RuntimeException {
        PublicationConflictException(String message) {
            super(message);
        }

        PublicationConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
