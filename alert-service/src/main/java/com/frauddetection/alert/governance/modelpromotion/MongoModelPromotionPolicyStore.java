package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.MongoException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class MongoModelPromotionPolicyStore implements ModelPromotionPolicyStore {

    private final MongoTemplate mongoTemplate;

    public MongoModelPromotionPolicyStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate);
    }

    @Override
    public Optional<ModelPromotionPolicy> findPolicy(
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            Instant assessmentTime
    ) {
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        Objects.requireNonNull(assessmentTime, "assessmentTime is required");
        Query query = Query.query(Criteria.where("institutional_scope").is(governanceScope.institutionalScope())
                        .and("environment").is(governanceScope.environment())
                        .and("intended_use").is(intendedUse)
                        .and("effective_from").lte(assessmentTime)
                        .and("effective_to").gt(assessmentTime))
                .with(Sort.by(Sort.Direction.DESC, "effective_from")
                        .and(Sort.by(Sort.Direction.DESC, "policy_revision")))
                .limit(2);
        try {
            List<ModelPromotionPolicyDocument> policies = mongoTemplate.find(query, ModelPromotionPolicyDocument.class);
            if (policies.size() > 1) {
                throw new ModelPromotionPolicyStoreUnavailableException("More than one policy is effective for the requested scope");
            }
            return policies.stream().findFirst().map(ModelPromotionPolicyDocument::policy);
        } catch (ModelPromotionPolicyStoreUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | MongoException exception) {
            throw new ModelPromotionPolicyStoreUnavailableException("Model promotion policy store is unavailable", exception);
        } catch (RuntimeException exception) {
            throw new ModelPromotionPolicyStoreUnavailableException("Model promotion policy store is invalid", exception);
        }
    }
}
