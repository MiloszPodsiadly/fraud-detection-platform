package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("invariant-proof")
@Tag("integration")
class MongoModelPromotionPolicyStoreIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private MongoModelPromotionPolicyStore store;

    @BeforeEach
    void setUp() {
        FraudPlatformContainers.startAll();
        String databaseName = "model_promotion_policy_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName));
        mongoTemplate = new MongoTemplate(databaseFactory);
        createIndexes();
        store = new MongoModelPromotionPolicyStore(mongoTemplate);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    @Test
    void resolvesOnlyTheSingleEffectiveRevision() {
        ModelPromotionPolicy first = policy("policy-1", 1, "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z");
        ModelPromotionPolicy second = policy("policy-1", 2, "2026-07-01T00:00:00Z", "2027-01-01T00:00:00Z");
        mongoTemplate.insert(ModelPromotionPolicyDocument.from(first));
        mongoTemplate.insert(ModelPromotionPolicyDocument.from(second));

        assertThat(store.findPolicy(scope(), use(), Instant.parse("2026-06-11T00:00:00Z")))
                .contains(first);
        assertThat(store.findPolicy(scope(), use(), Instant.parse("2026-08-11T00:00:00Z")))
                .contains(second);
    }

    @Test
    void revisionCannotBeReplacedWithDifferentCanonicalContent() {
        ModelPromotionPolicy first = policy("policy-1", 1, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        ModelPromotionPolicy modified = policy("policy-1", 1, "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z");
        mongoTemplate.insert(ModelPromotionPolicyDocument.from(first));

        assertThatThrownBy(() -> mongoTemplate.insert(ModelPromotionPolicyDocument.from(modified)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(store.findPolicy(scope(), use(), Instant.parse("2026-06-11T00:00:00Z")))
                .contains(first);
    }

    @Test
    void overlappingEffectivePoliciesFailClosedInsteadOfSelectingOne() {
        mongoTemplate.insert(ModelPromotionPolicyDocument.from(
                policy("policy-1", 1, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z")
        ));
        mongoTemplate.insert(ModelPromotionPolicyDocument.from(
                policy("policy-2", 1, "2026-06-01T00:00:00Z", "2027-06-01T00:00:00Z")
        ));

        assertThatThrownBy(() -> store.findPolicy(scope(), use(), Instant.parse("2026-06-11T00:00:00Z")))
                .isInstanceOf(ModelPromotionPolicyStoreUnavailableException.class)
                .hasMessageContaining("More than one policy");
    }

    private void createIndexes() {
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
    }

    private ModelPromotionPolicy policy(String policyId, long revision, String effectiveFrom, String effectiveTo) {
        ModelPromotionPolicy source = ModelPromotionAssessmentTestFixtures.policy();
        ModelPromotionPolicy.Content content = new ModelPromotionPolicy.Content(
                source.content().policyVersion(),
                policyId,
                revision,
                scope(),
                use(),
                source.content().provenance(),
                effectiveFrom,
                effectiveTo,
                source.content().criteria()
        );
        return new ModelPromotionPolicy(content, new ModelPromotionPolicyValidator(new ObjectMapper()).canonicalSha256(content));
    }

    private ModelGovernanceScope scope() {
        return ModelPromotionAssessmentTestFixtures.scope();
    }

    private ModelPromotionIntendedUse use() {
        return ModelPromotionIntendedUse.FRAUD_RISK_SCORING;
    }
}
