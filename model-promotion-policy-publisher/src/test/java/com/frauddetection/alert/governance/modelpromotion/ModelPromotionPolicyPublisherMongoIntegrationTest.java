package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionPolicyPublisherMongoIntegrationTest {

    private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String ISSUER = "institutional-policy-issuer";
    private static final String KEY_ID = "policy-key-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private SimpleMongoClientDatabaseFactory factory;
    private MongoTemplate mongo;
    private MongoModelPromotionPolicyPublicationStore store;
    private ModelPromotionPolicyAuthorityVerifier verifier;
    private KeyPair issuerKey;
    private KeyPair approverKey;

    @BeforeAll
    static void startMongo() {
        MONGO.start();
    }

    @AfterAll
    static void stopMongo() {
        MONGO.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        String database = "policy_publish_" + UUID.randomUUID().toString().replace("-", "");
        factory = new SimpleMongoClientDatabaseFactory(MONGO.getReplicaSetUrl(database));
        mongo = new MongoTemplate(factory);
        store = store(factory, mongo);
        issuerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        approverKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier = verifier(issuerKey);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongo != null) {
            mongo.getDb().drop();
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void approvedPolicyPublishesAndResolverRetrievesRealVerifiedPolicy() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy proposal = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ModelPromotionPolicyPublisher publisher = publisher(approval(proposal), store);

        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher.prepare(
                proposal, issuerKey.getPrivate(), ISSUER, KEY_ID);
        assertThat(verifier.verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
                ModelPromotionPolicyDocument.class)).isZero();
        ModelPromotionPolicyPublicationReceipt receipt = publisher.publishPrepared(prepared);

        assertThat(receipt.policyId()).isEqualTo("policy-1");
        assertThat(receipt.revision()).isEqualTo(1);
        assertThat(receipt.canonicalSha256()).isEqualTo(proposal.canonicalSha256());
        assertThat(receipt.issuerIdentity()).isEqualTo(ISSUER);
        assertThat(receipt.keyId()).isEqualTo(KEY_ID);
        assertThat(receipt.publishedAt()).isEqualTo(CLOCK.instant());
        assertThat(receipt.alreadyPublished()).isFalse();
        ModelPromotionPolicyResolver resolver = new ModelPromotionPolicyResolver(
                new MongoModelPromotionPolicyStore(mongo), new ModelPromotionPolicyValidator(mapper), verifier);
        ModelPromotionPolicyResolution resolved = resolver.resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(resolved.status()).isEqualTo(ModelPromotionPolicyResolution.Status.AVAILABLE);
        assertThat(resolved.policy()).isEqualTo(prepared.policy());
    }

    @Test
    void exactRepublishIsIdempotentAndKeepsOriginalPublicationTime() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy proposal = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ModelPromotionPolicyPublisher publisher = publisher(approval(proposal), store);
        ModelPromotionPolicyPublicationReceipt first = publisher.publish(proposal, issuerKey.getPrivate(), ISSUER, KEY_ID);
        ModelPromotionPolicyPublicationReceipt repeat = publisher.publish(proposal, issuerKey.getPrivate(), ISSUER, KEY_ID);

        assertThat(repeat.alreadyPublished()).isTrue();
        assertThat(repeat.publishedAt()).isEqualTo(first.publishedAt());
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
                ModelPromotionPolicyDocument.class)).isEqualTo(1);
    }

    @Test
    void conflictingRevisionAndOverlappingPeriodAreRejectedWithoutReplacingHistory() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy first = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        publisher(approval(first), store).publish(first, issuerKey.getPrivate(), ISSUER, KEY_ID);
        ModelPromotionPolicyPublisher.UnsignedPolicy changedRevision = proposal("policy-1", 1,
                "2026-10-01T00:00:00Z", "2026-12-01T00:00:00Z");
        ModelPromotionPolicyPublisher.UnsignedPolicy overlap = proposal("policy-2", 1,
                "2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z");

        assertThatThrownBy(() -> publisher(approval(changedRevision), store).publish(changedRevision,
                issuerKey.getPrivate(), ISSUER, KEY_ID))
                .isInstanceOf(MongoModelPromotionPolicyPublicationStore.PublicationConflictException.class);
        assertThatThrownBy(() -> publisher(approval(overlap), store).publish(overlap,
                issuerKey.getPrivate(), ISSUER, KEY_ID))
                .isInstanceOf(MongoModelPromotionPolicyPublicationStore.PublicationConflictException.class);
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
                ModelPromotionPolicyDocument.class)).isEqualTo(1);
    }

    @Test
    void concurrentOverlappingWritersCannotBothCommit() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy first = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ModelPromotionPolicyPublisher.UnsignedPolicy second = proposal("policy-2", 1,
                "2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z");
        ModelPromotionPolicyPublisher.PreparedPublication preparedFirst = publisher(approval(first), store)
                .prepare(first, issuerKey.getPrivate(), ISSUER, KEY_ID);
        ModelPromotionPolicyPublisher.PreparedPublication preparedSecond = publisher(approval(second), store)
                .prepare(second, issuerKey.getPrivate(), ISSUER, KEY_ID);
        CountDownLatch release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> a = workers.submit(() -> attemptPublication(preparedFirst, release));
            Future<Boolean> b = workers.submit(() -> attemptPublication(preparedSecond, release));
            release.countDown();
            assertThat(List.of(a.get(), b.get()).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
                ModelPromotionPolicyDocument.class)).isEqualTo(1);
    }

    @Test
    void invalidSignatureMissingApprovalChangedDigestAndUnauthorizedIssuerNeverInsert() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy proposal = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        KeyPair wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThatThrownBy(() -> publisher(approval(proposal), store).publish(proposal,
                wrongKey.getPrivate(), ISSUER, KEY_ID)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> publisher((content, digest) -> Optional.empty(), store).publish(proposal,
                issuerKey.getPrivate(), ISSUER, KEY_ID)).isInstanceOf(SecurityException.class);
        ModelPromotionPolicyPublisher.UnsignedPolicy modified = proposal("policy-1", 2,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        assertThatThrownBy(() -> publisher(approval(proposal), store).publish(modified,
                issuerKey.getPrivate(), ISSUER, KEY_ID)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> publisher(approval(proposal), store).publish(proposal,
                issuerKey.getPrivate(), "unauthorized-issuer", KEY_ID)).isInstanceOf(SecurityException.class);
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
                ModelPromotionPolicyDocument.class)).isZero();
    }

    @Test
    void mongoFailureCannotReportPublicationSuccessAndRestartPreservesInvariants() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy first = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher(approval(first), store)
                .prepare(first, issuerKey.getPrivate(), ISSUER, KEY_ID);
        SimpleMongoClientDatabaseFactory unavailableFactory = new SimpleMongoClientDatabaseFactory(
                "mongodb://127.0.0.1:1/unavailable?serverSelectionTimeoutMS=200");
        try {
            MongoModelPromotionPolicyPublicationStore unavailable = store(unavailableFactory,
                    new MongoTemplate(unavailableFactory));
            assertThatThrownBy(() -> unavailable.publish(prepared.policy(), CLOCK.instant()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            unavailableFactory.destroy();
        }
        ModelPromotionPolicyPublicationReceipt firstReceipt = store.publish(prepared.policy(), CLOCK.instant());
        assertThat(firstReceipt.alreadyPublished()).isFalse();
        SimpleMongoClientDatabaseFactory restartedFactory = new SimpleMongoClientDatabaseFactory(
                MONGO.getReplicaSetUrl(mongo.getDb().getName()));
        try {
            MongoModelPromotionPolicyPublicationStore restarted = store(restartedFactory,
                    new MongoTemplate(restartedFactory));
            assertThat(restarted.publish(prepared.policy(), CLOCK.instant()).alreadyPublished()).isTrue();
            ModelPromotionPolicyPublisher.UnsignedPolicy overlap = proposal("policy-2", 1,
                    "2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z");
            ModelPromotionPolicyPublisher.PreparedPublication conflicting = publisher(approval(overlap), restarted)
                    .prepare(overlap, issuerKey.getPrivate(), ISSUER, KEY_ID);
            assertThatThrownBy(() -> restarted.publish(conflicting.policy(), CLOCK.instant()))
                    .isInstanceOf(MongoModelPromotionPolicyPublicationStore.PublicationConflictException.class);
        } finally {
            restartedFactory.destroy();
        }
    }

    private boolean attemptPublication(ModelPromotionPolicyPublisher.PreparedPublication prepared,
            CountDownLatch release) throws InterruptedException {
        release.await();
        try {
            store.publish(prepared.policy(), CLOCK.instant());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private MongoModelPromotionPolicyPublicationStore store(SimpleMongoClientDatabaseFactory source,
            MongoTemplate template) {
        return new MongoModelPromotionPolicyPublicationStore(template,
                new TransactionTemplate(new MongoTransactionManager(source)));
    }

    private ModelPromotionPolicyPublisher publisher(InstitutionalPolicyPublicationAuthorization approval,
            MongoModelPromotionPolicyPublicationStore storage) {
        return new ModelPromotionPolicyPublisher(mapper, new ModelPromotionPolicyValidator(mapper), approval,
                verifier, storage, CLOCK);
    }

    private ModelPromotionPolicyAuthorityVerifier verifier(KeyPair pair) throws Exception {
        byte[] publicKey = pair.getPublic().getEncoded();
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey key = new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, KEY_ID, Base64.getEncoder().encodeToString(publicKey),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey)),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION), List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                "2025-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
        ModelPromotionTrustedIssuerRegistry registry = ModelPromotionTrustedIssuerRegistry.fromConfiguration(
                new ModelPromotionTrustedIssuerRegistry.Configuration(ModelPromotionTrustedIssuerRegistry.VERSION,
                        1, List.of(key)), 1);
        return new Ed25519ModelPromotionPolicyAuthorityVerifier(mapper, registry, CLOCK);
    }

    private InstitutionalPolicyPublicationAuthorization approval(ModelPromotionPolicyPublisher.UnsignedPolicy proposal)
            throws Exception {
        ModelPromotionPolicy.Content content = proposal.content();
        InstitutionalPolicyPublicationAuthorization.Authorization authorization =
                new InstitutionalPolicyPublicationAuthorization.Authorization(content.policyId(), content.revision(),
                        proposal.canonicalSha256(), content.governanceScope(), content.intendedUse(),
                        Instant.parse(content.effectiveFromInclusive()), Instant.parse(content.effectiveToExclusive()),
                        content.provenance().authorIdentity(), content.provenance().approvingAuthority(),
                        content.provenance().approvalReference(), "authorized-publisher", ISSUER, KEY_ID);
        byte[] message = approvalBytes(authorization);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(approverKey.getPrivate());
        signer.update(message);
        byte[] signature = signer.sign();
        return (requested, digest) -> {
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(approverKey.getPublic());
                verifier.update(approvalBytes(authorization));
                return verifier.verify(signature) ? Optional.of(authorization) : Optional.empty();
            } catch (Exception exception) {
                return Optional.empty();
            }
        };
    }

    private byte[] approvalBytes(InstitutionalPolicyPublicationAuthorization.Authorization authorization) {
        return GovernanceCanonicalJson.serialize(mapper, mapper.valueToTree(Map.of(
                "purpose", "TEST_ONLY_INDEPENDENT_POLICY_APPROVAL",
                "authorization", authorization)));
    }

    private ModelPromotionPolicyPublisher.UnsignedPolicy proposal(String policyId, long revision,
            String effectiveFrom, String effectiveTo) {
        List<ModelPromotionPolicy.CriterionRule> rules = java.util.Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(java.util.stream.Collectors.toMap(
                                key -> key, key -> BigDecimal.ONE)),
                        86400, "VERIFIED", criterion.evidenceSource(), criterion.metricSemantics(),
                        criterion.populationDefinition(), criterion.denominatorDefinition(), null))
                .toList();
        ModelPromotionPolicy.Content content = new ModelPromotionPolicy.Content(ModelPromotionPolicy.CURRENT_VERSION,
                policyId, revision, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                new ModelPromotionPolicy.PolicyProvenance(ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        "policy-author", "risk-committee", "2026-05-01T00:00:00Z", "approval-1"),
                effectiveFrom, effectiveTo, rules);
        return new ModelPromotionPolicyPublisher.UnsignedPolicy(content,
                new ModelPromotionPolicyValidator(mapper).canonicalSha256(content));
    }

    private ModelGovernanceScope scope() {
        return new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                ModelGovernanceEnvironment.PRODUCTION);
    }
}
