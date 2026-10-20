package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.modelcard.ModelCardProvider;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceProvider;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    private KeyPair authorKey;
    private KeyPair publisherKey;

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
        authorKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publisherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier = verifier(issuerKey);
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        if (mongo != null) {
            mongo.getDb().drop();
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void realApprovedSignedPolicyAndIndependentModelReviewCommitThroughOneMongoBoundary() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy exactPolicy = proposal("policy-full-e2e", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        InternalPolicyApprovalArtifact proof = firstPartyProof(exactPolicy);
        InternalPolicyPublicationAuthorization approval = new InternalPolicyPublicationAuthorization(
                mapper, firstPartyTrust(), proof, publisherKey.getPrivate(), CLOCK);
        assertThat(approval.authorize(exactPolicy.content(), exactPolicy.canonicalSha256())).isPresent();
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher(approval, store)
                .prepare(exactPolicy, issuerKey.getPrivate(), ISSUER, KEY_ID);
        assertThat(verifier.verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isZero();
        store.publish(prepared.policy(), CLOCK.instant(), proof, prepared.authorization());

        ModelPromotionPolicyResolver resolver = new ModelPromotionPolicyResolver(
                new MongoModelPromotionPolicyStore(mongo), new ModelPromotionPolicyValidator(mapper), verifier);
        Instant decisionTime = Instant.parse("2026-08-01T00:00:00Z");
        ModelPromotionPolicyResolution resolved = resolver.resolve(scope(),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, decisionTime);
        assertThat(resolved.status()).isEqualTo(ModelPromotionPolicyResolution.Status.AVAILABLE);
        assertThat(resolved.policy()).isEqualTo(prepared.policy());
        assertThat(mongo.findAll(InternalPolicyApprovalEvidenceDocument.class)).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.approvalProof()).isEqualTo(proof);
                    assertThat(evidence.approvalProofSha256())
                            .isEqualTo(InternalPolicyApprovalArtifact.proofSha256(mapper, proof));
                    assertThat(evidence.publisherIdentity()).isEqualTo("policy-publisher");
                });

        new ModelPromotionPersistenceIndexInitializer(mongo).ensureIndexes();
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> managerProvider = mock(ObjectProvider.class);
        when(managerProvider.getIfAvailable()).thenReturn(new MongoTransactionManager(factory));
        MongoModelPromotionStore modelStore = new MongoModelPromotionStore(mongo,
                new RegulatedMutationTransactionRunner("REQUIRED", managerProvider),
                new ModelPromotionDecisionValidator(mapper, new ModelPromotionPolicyValidator(mapper), verifier),
                new ModelPromotionLocalAuditFactory(), Clock.fixed(decisionTime, ZoneOffset.UTC));
        ModelCardProvider cards = mock(ModelCardProvider.class);
        ExactModelShadowPerformanceProvider shadow = mock(ExactModelShadowPerformanceProvider.class);
        ExactModelArtifactIdentity identity = new ExactModelArtifactIdentity(
                "signed-policy-e2e-model", "2026.08.01", "feature-contract-v2", "a".repeat(64));
        when(cards.findExact(identity)).thenReturn(Optional.empty());
        ModelPromotionWorkflowService workflow = new ModelPromotionWorkflowService(modelStore,
                new ModelPromotionEvidenceResolver(cards, shadow, mapper),
                new ModelPromotionAssessmentService(resolver, mapper), resolver,
                new ModelPromotionAuthorization(new CurrentAnalystUser()), Clock.fixed(decisionTime, ZoneOffset.UTC));
        ModelPromotionEvaluationWindow window = new ModelPromotionEvaluationWindow(
                "2026-07-01T00:00:00Z", "2026-07-31T23:59:59Z");
        String rationale = "Independent exact-model governance review";

        authenticate("model-governance-actor-c", Set.of(AnalystAuthority.MODEL_PROMOTION_REGISTER,
                AnalystAuthority.MODEL_PROMOTION_PROPOSE, AnalystAuthority.MODEL_PROMOTION_APPROVE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
        workflow.registerCandidate(new ModelPromotionWorkflowService.CandidateRegistrationRequest(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, "register-e2e"));
        ModelPromotionWorkflowService.TransitionProposalRequest request =
                new ModelPromotionWorkflowService.TransitionProposalRequest(identity, scope(),
                        ModelPromotionIntendedUse.FRAUD_RISK_SCORING, ModelPromotionLifecycleState.CANDIDATE, 0,
                        ModelPromotionLifecycleState.CHALLENGER, window, "submit-e2e", "proposal-e2e",
                        rationale, ModelPromotionDecisionRationale.sha256(rationale));
        ModelPromotionProposalResult pending = workflow.proposeTransition(request);
        assertThat(pending.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(modelStore.currentState(identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING)
                .orElseThrow().lifecycleState()).isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        ModelPromotionWorkflowService.ReviewRequest review = new ModelPromotionWorkflowService.ReviewRequest(
                "proposal-e2e", "approval-e2e", ModelPromotionApproval.Outcome.APPROVED,
                "review-e2e", "Independent model decision",
                ModelPromotionDecisionRationale.sha256("Independent model decision"));
        assertThatThrownBy(() -> workflow.reviewProposal(review))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("cannot review their own proposal");

        authenticate("independent-reviewer-d", Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
        ModelPromotionProposalResult accepted = workflow.reviewProposal(review);
        assertThat(accepted.idempotentReplay()).isFalse();
        assertThat(workflow.reviewProposal(review).idempotentReplay()).isTrue();
        ModelPromotionAggregate aggregate = modelStore.currentState(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING).orElseThrow();
        assertThat(aggregate.lifecycleState()).isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(aggregate.revision()).isEqualTo(1);
        assertThat(aggregate.acceptedDecisionId()).isNotBlank();
        assertThat(modelStore.history(identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 10))
                .hasSize(2).allSatisfy(decision -> assertThat(decision.localAudit()).isNotNull());
        assertThat(mongo.count(new Query(), ModelPromotionDecisionDocument.class)).isEqualTo(2);
        assertThat(mongo.findById("proposal-e2e", ModelPromotionProposalDocument.class).reviewAudit()).isNotNull();

        authenticate("model-governance-actor-c", Set.of(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
        assertThatThrownBy(() -> workflow.proposeTransition(new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                ModelPromotionLifecycleState.CHALLENGER, 1, ModelPromotionLifecycleState.SHADOW_APPROVED,
                window, "submit-shadow-no-evidence", "proposal-shadow-no-evidence", rationale,
                ModelPromotionDecisionRationale.sha256(rationale))))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(error -> assertThat(((ModelPromotionWorkflowBlockedException) error).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.EVIDENCE_UNAVAILABLE));
        assertThat(modelStore.currentState(identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING)
                .orElseThrow().lifecycleState()).isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(mongo.count(new Query(), ModelPromotionProposalDocument.class)).isEqualTo(1);
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
    void firstPartyProofAndPolicyCommitTogetherAndRemainAuditableAfterRestart() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy proposal = proposal("policy-first-party", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        InternalPolicyApprovalArtifact proof = firstPartyProof(proposal);
        InternalPolicyPublicationAuthorization approval = new InternalPolicyPublicationAuthorization(
                mapper, firstPartyTrust(), proof, publisherKey.getPrivate(), CLOCK);
        assertThat(approval.authorize(proposal.content(), proposal.canonicalSha256())).isPresent();
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher(approval, store)
                .prepare(proposal, issuerKey.getPrivate(), ISSUER, KEY_ID);
        ModelPromotionPolicyPublicationReceipt first = store.publish(prepared.policy(), CLOCK.instant(), proof,
                prepared.authorization());
        assertThat(first.alreadyPublished()).isFalse();
        InternalPolicyApprovalEvidenceDocument evidence = mongo.findAll(
                InternalPolicyApprovalEvidenceDocument.class).getFirst();
        assertThat(evidence.approvalProof()).isEqualTo(proof);
        assertThat(evidence.approvalProofSha256()).isEqualTo(InternalPolicyApprovalArtifact.proofSha256(mapper, proof));
        assertThat(evidence.policySignature()).isEqualTo(prepared.policy().authorizationEvidence().signature());
        assertThat(evidence.approverIdentity()).isEqualTo("risk-committee");
        assertThat(evidence.publisherIdentity()).isEqualTo("policy-publisher");
        assertThat(evidence.publisherMandate()).isEqualTo(InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER);
        assertThat(evidence.publisherPublicKeySha256())
                .isEqualTo(prepared.authorization().publisherPublicKeySha256());
        assertThat(evidence.approvalTrustSha256()).isEqualTo(firstPartyTrust().canonicalSha256());
        assertThat(verifier.verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        SimpleMongoClientDatabaseFactory restartedFactory = new SimpleMongoClientDatabaseFactory(
                MONGO.getReplicaSetUrl(mongo.getDb().getName()));
        try {
            MongoTemplate restartedMongo = new MongoTemplate(restartedFactory);
            MongoModelPromotionPolicyPublicationStore restarted = store(restartedFactory, restartedMongo);
            assertThat(restarted.publish(prepared.policy(), CLOCK.instant().plusSeconds(1), proof,
                    prepared.authorization())
                    .alreadyPublished()).isTrue();
            assertThat(restartedMongo.findAll(InternalPolicyApprovalEvidenceDocument.class)).hasSize(1);
        } finally {
            restartedFactory.destroy();
        }
    }

    @Test
    void failedEvidenceInsertRollsBackPolicyInsert() throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy proposal = proposal("policy-rollback", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        InternalPolicyApprovalArtifact proof = firstPartyProof(proposal);
        InternalPolicyPublicationAuthorization approval = new InternalPolicyPublicationAuthorization(
                mapper, firstPartyTrust(), proof, publisherKey.getPrivate(), CLOCK);
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher(approval, store)
                .prepare(proposal, issuerKey.getPrivate(), ISSUER, KEY_ID);
        String key = ModelPromotionPolicyDocument.from(prepared.policy()).policyKey();
        mongo.insert(new org.bson.Document("_id", key), InternalPolicyApprovalEvidenceDocument.COLLECTION);
        assertThatThrownBy(() -> store.publish(prepared.policy(), CLOCK.instant(), proof,
                prepared.authorization()))
                .isInstanceOf(RuntimeException.class);
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isZero();
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
        InternalPolicyApprovalArtifact valid = firstPartyProof(proposal);
        InternalPolicyApprovalArtifact unsigned = new InternalPolicyApprovalArtifact(valid.authorStatement(),
                valid.authorSignature(), valid.approvalDecision(), "invalid-signature");
        InternalPolicyPublicationAuthorization invalid = new InternalPolicyPublicationAuthorization(
                mapper, firstPartyTrust(), unsigned, publisherKey.getPrivate(), CLOCK);
        assertThatThrownBy(() -> publisher(invalid, store).publish(proposal,
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
            assertThatThrownBy(() -> unavailable.publish(prepared.policy(), CLOCK.instant(),
                    prepared.approvalArtifact(), prepared.authorization()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            unavailableFactory.destroy();
        }
        ModelPromotionPolicyPublicationReceipt firstReceipt = store.publish(prepared.policy(), CLOCK.instant(),
                prepared.approvalArtifact(), prepared.authorization());
        assertThat(firstReceipt.alreadyPublished()).isFalse();
        SimpleMongoClientDatabaseFactory restartedFactory = new SimpleMongoClientDatabaseFactory(
                MONGO.getReplicaSetUrl(mongo.getDb().getName()));
        try {
            MongoModelPromotionPolicyPublicationStore restarted = store(restartedFactory,
                    new MongoTemplate(restartedFactory));
            assertThat(restarted.publish(prepared.policy(), CLOCK.instant(), prepared.approvalArtifact(),
                    prepared.authorization())
                    .alreadyPublished()).isTrue();
            ModelPromotionPolicyPublisher.UnsignedPolicy overlap = proposal("policy-2", 1,
                    "2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z");
            ModelPromotionPolicyPublisher.PreparedPublication conflicting = publisher(approval(overlap), restarted)
                    .prepare(overlap, issuerKey.getPrivate(), ISSUER, KEY_ID);
            assertThatThrownBy(() -> restarted.publish(conflicting.policy(), CLOCK.instant(),
                    conflicting.approvalArtifact(), conflicting.authorization()))
                    .isInstanceOf(MongoModelPromotionPolicyPublicationStore.PublicationConflictException.class);
        } finally {
            restartedFactory.destroy();
        }
    }

    private boolean attemptPublication(ModelPromotionPolicyPublisher.PreparedPublication prepared,
            CountDownLatch release) throws InterruptedException {
        release.await();
        try {
            store.publish(prepared.policy(), CLOCK.instant(), prepared.approvalArtifact(),
                    prepared.authorization());
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

    private ModelPromotionPolicyPublisher publisher(InternalPolicyPublicationAuthorization approval,
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

    private InternalPolicyPublicationAuthorization approval(ModelPromotionPolicyPublisher.UnsignedPolicy proposal)
            throws Exception {
        return new InternalPolicyPublicationAuthorization(mapper, firstPartyTrust(), firstPartyProof(proposal),
                publisherKey.getPrivate(), CLOCK);
    }

    private InternalPolicyApprovalArtifact firstPartyProof(ModelPromotionPolicyPublisher.UnsignedPolicy proposal)
            throws Exception {
        ModelPromotionPolicy.Content content = proposal.content();
        InternalPolicyApprovalArtifact.AuthorStatement statement = new InternalPolicyApprovalArtifact.AuthorStatement(
                InternalPolicyApprovalArtifact.VERSION, InternalPolicyApprovalArtifact.AUTHOR_PURPOSE,
                content.policyId(), content.revision(), proposal.canonicalSha256(), content.governanceScope(),
                content.intendedUse(), content.effectiveFromInclusive(), content.effectiveToExclusive(),
                "policy-author", "author-key-1", "policy-publisher", "publisher-key-1", ISSUER, KEY_ID,
                "2026-04-01T00:00:00Z");
        InternalPolicyApprovalArtifact.ApprovalDecision decision = new InternalPolicyApprovalArtifact.ApprovalDecision(
                InternalPolicyApprovalArtifact.VERSION, InternalPolicyApprovalArtifact.APPROVER_PURPOSE,
                InternalPolicyApprovalArtifact.authorStatementSha256(mapper, statement), "risk-committee",
                "approver-key-1", InternalPolicyApprovalArtifact.Decision.APPROVED,
                content.provenance().approvedAt(), "2026-06-15T00:00:00Z",
                content.provenance().approvalReference());
        return new InternalPolicyApprovalArtifact(statement,
                sign(authorKey, InternalPolicyApprovalArtifact.authorSigningBytes(mapper, statement)), decision,
                sign(approverKey, InternalPolicyApprovalArtifact.approverSigningBytes(mapper, decision)));
    }

    private String sign(KeyPair pair, byte[] input) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(input);
        return ModelPromotionPolicySignatureContract.encodeSignature(signer.sign());
    }

    private InternalPolicyApprovalTrustRegistry firstPartyTrust() throws Exception {
        return InternalPolicyApprovalTrustRegistry.fromConfiguration(
                new InternalPolicyApprovalTrustRegistry.Configuration(InternalPolicyApprovalTrustRegistry.VERSION,
                        1, List.of(
                        actor("policy-author", "author-key-1", authorKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR),
                        actor("risk-committee", "approver-key-1", approverKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER),
                        actor("policy-publisher", "publisher-key-1", publisherKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER),
                        actor(ISSUER, KEY_ID, issuerKey, InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER))), 1);
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey actor(String identity, String keyId, KeyPair pair,
            InternalPolicyApprovalTrustRegistry.Role role) throws Exception {
        byte[] encoded = pair.getPublic().getEncoded();
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(identity, keyId,
                Base64.getEncoder().encodeToString(encoded),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                List.of(role), List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION), List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                "2025-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
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

    private void authenticate(String actorId, Set<String> authorities) {
        AnalystPrincipal principal = new AnalystPrincipal(actorId, Set.of(), authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new AnalystAuthenticationFactory().authenticated(principal));
    }
}
