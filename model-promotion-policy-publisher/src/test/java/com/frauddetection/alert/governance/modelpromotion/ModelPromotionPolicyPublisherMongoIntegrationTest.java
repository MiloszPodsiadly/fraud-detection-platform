package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelcard.ModelCardProvider;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceProvider;
import com.frauddetection.alert.governance.modelpolicy.GovernanceBoundWebAuthnProof;
import com.frauddetection.alert.governance.modelpolicy.GovernanceWebAuthnBoundChallenge;
import com.frauddetection.alert.governance.modelpolicy.MongoPolicyGovernanceStore;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnCredential;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnCredentialRepository;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yubico.webauthn.AssertionRequest;
import com.upokecenter.cbor.CBORObject;
import com.mongodb.MongoException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.env.MockEnvironment;
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
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

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
    void realApprovedSignedPolicyPublishesAndUnboundProductionReviewIsRejectedAtomically() throws Exception {
        ModelPromotionPolicyPublisher.Draft exactPolicy = proposal("policy-full-e2e", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        InternalPolicyApprovalArtifact proof = firstPartyProof(exactPolicy);
        InternalPolicyPublicationAuthorization approval = new InternalPolicyPublicationAuthorization(
                mapper, firstPartyTrust(), proof, publisherKey.getPrivate(), CLOCK);
        ModelPromotionPolicy.Content approvedContent = exactPolicy.content().approvedContent(
                Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(approval.authorize(approvedContent,
                new ModelPromotionPolicyValidator(mapper).canonicalSha256(approvedContent))).isPresent();
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher(approval, store)
                .prepare(exactPolicy, issuerKey.getPrivate(), ISSUER, KEY_ID);
        assertThat(verifier.verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isZero();
        store.publish(prepared.policy(), CLOCK.instant(), proof, prepared.authorization());

        ModelPromotionPolicyResolver resolver = new ModelPromotionPolicyResolver(
                new MongoModelPromotionPolicyStore(mongo), new ModelPromotionPolicyValidator(mapper), fullTrust());
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
                new ModelPromotionDecisionValidator(mapper, new ModelPromotionPolicyValidator(mapper), fullTrust()),
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
        long decisionsBeforeUnboundReview = mongo.count(new Query(), ModelPromotionDecisionDocument.class);
        long historyBeforeUnboundReview = modelStore.history(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 10).size();
        assertThatThrownBy(() -> workflow.reviewProposal(review))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("requires a bound WebAuthn assertion");
        ModelPromotionAggregate aggregate = modelStore.currentState(
                identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING).orElseThrow();
        assertThat(aggregate.lifecycleState()).isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(aggregate.revision()).isZero();
        assertThat(aggregate.acceptedDecisionId()).isNull();
        assertThat(modelStore.history(identity, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 10))
                .hasSize((int) historyBeforeUnboundReview);
        assertThat(mongo.count(new Query(), ModelPromotionDecisionDocument.class))
                .isEqualTo(decisionsBeforeUnboundReview);
        ModelPromotionProposalDocument unchangedProposal = mongo.findById(
                "proposal-e2e", ModelPromotionProposalDocument.class);
        assertThat(unchangedProposal.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(unchangedProposal.reviewAudit()).isNull();

    }

    @Test
    void verifiedProductionSignedReviewCommitsProofAndRecoversLostAcknowledgement() throws Exception {
        Instant decisionTime = Instant.parse("2026-08-02T00:00:00Z");
        ModelPromotionPolicyPublisher.Draft draft = productionReviewProposal("policy-prod-review", 1,
                "2026-07-01T00:00:00Z", "2027-10-01T00:00:00Z");
        InternalPolicyApprovalArtifact approvalProof = firstPartyProof(draft);
        InternalPolicyPublicationAuthorization approval = new InternalPolicyPublicationAuthorization(
                mapper, firstPartyTrust(), approvalProof, publisherKey.getPrivate(), CLOCK);
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher(approval, store)
                .prepare(draft, issuerKey.getPrivate(), ISSUER, KEY_ID);
        assertThat(verifier.verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        publisher(approval, store).publishPrepared(prepared);

        ModelGovernanceScope governanceScope = scope();
        ModelPromotionIntendedUse intendedUse = ModelPromotionIntendedUse.FRAUD_RISK_SCORING;
        ModelPromotionPolicyFullTrustVerifier trust = fullTrust();
        ModelPromotionPolicyResolver policyResolver = new ModelPromotionPolicyResolver(
                new MongoModelPromotionPolicyStore(mongo), new ModelPromotionPolicyValidator(mapper), trust);
        ModelPromotionPolicyResolution policyResolution = policyResolver.resolve(
                governanceScope, intendedUse, decisionTime);
        assertThat(policyResolution.status()).isEqualTo(ModelPromotionPolicyResolution.Status.AVAILABLE);
        assertThat(policyResolution.policy()).isEqualTo(prepared.policy());
        assertThat(trust.verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyTrustVerification.Status.FULLY_AUTHORIZED);

        new ModelPromotionPersistenceIndexInitializer(mongo).ensureIndexes();
        MongoTransactionManager transactionManager = new MongoTransactionManager(factory);
        ModelPromotionDecisionValidator decisionValidator = new ModelPromotionDecisionValidator(
                mapper, new ModelPromotionPolicyValidator(mapper), trust);
        MongoModelPromotionStore promotionStore = modelPromotionStore(
                mongo, transactionManager, decisionValidator, null);
        ExactModelArtifactIdentity identity = new ExactModelArtifactIdentity(
                "production-review-model", "2026.08.01", "feature-contract-v2", "a".repeat(64));
        ModelPromotionEvaluationWindow window = new ModelPromotionEvaluationWindow(
                "2026-07-01T00:00:00Z", "2026-07-31T23:59:59Z");
        ModelPromotionEvidenceBundle evidence = productionReviewEvidence(identity, governanceScope, window);
        ModelPromotionEvidenceResolver evidenceResolver = mock(ModelPromotionEvidenceResolver.class);
        when(evidenceResolver.resolve(identity, governanceScope, intendedUse, window))
                .thenReturn(ModelPromotionEvidenceResolution.available(evidence));
        ModelPromotionAssessmentService assessmentService = new ModelPromotionAssessmentService(policyResolver, mapper);
        ModelPromotionAuthorization authorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        ModelPromotionWorkflowService workflow = new ModelPromotionWorkflowService(promotionStore, evidenceResolver,
                assessmentService, policyResolver, authorization, Clock.fixed(decisionTime, ZoneOffset.UTC));

        String proposerIssuer = "https://institution.example";
        String proposerSubject = "promotion-proposer";
        String reviewerIssuer = "https://institution.example";
        String reviewerSubject = "independent-reviewer";
        String reviewerId = PolicyGovernanceActor.identityId(reviewerIssuer, reviewerSubject);
        PolicyGovernanceActor reviewer = new PolicyGovernanceActor(reviewerId,
                Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION),
                reviewerIssuer, reviewerSubject);
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair assertionKey = ec.generateKeyPair();
        ECPublicKey assertionPublicKey = (ECPublicKey) assertionKey.getPublic();
        String credentialId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("production-review-passkey".getBytes(StandardCharsets.UTF_8));
        String userHandle = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(HexFormat.of().parseHex(reviewerId.substring("oidc:".length())));
        CBORObject cose = CBORObject.NewMap().Add(1, 2).Add(3, -7).Add(-1, 1)
                .Add(-2, coseCoordinate(assertionPublicKey.getW().getAffineX()))
                .Add(-3, coseCoordinate(assertionPublicKey.getW().getAffineY()));
        mongo.insert(new PolicyWebAuthnCredential(credentialId, reviewerId, reviewerIssuer, reviewerSubject,
                userHandle, Base64.getUrlEncoder().withoutPadding().encodeToString(cose.EncodeToBytes()),
                0, false, CLOCK.instant(), CLOCK.instant(), "ACTIVE", "PRODUCTION", null, null, null, null));
        PolicyWebAuthnService webAuthn = policyWebAuthn(mongo, transactionManager, new MongoPolicyGovernanceStore(
                mongo, transactionManager));
        ModelPromotionSignedReviewService signedReview = new ModelPromotionSignedReviewService(
                promotionStore, authorization, workflow, webAuthn, mongo, mapper);

        authenticate(proposerIssuer, proposerSubject, Set.of(AnalystAuthority.MODEL_PROMOTION_REGISTER,
                AnalystAuthority.MODEL_PROMOTION_PROPOSE, AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
        workflow.registerCandidate(new ModelPromotionWorkflowService.CandidateRegistrationRequest(
                identity, governanceScope, intendedUse, "register-prod-review"));
        ModelPromotionProposalResult challengerProposal = workflow.proposeTransition(
                new ModelPromotionWorkflowService.TransitionProposalRequest(identity, governanceScope, intendedUse,
                        ModelPromotionLifecycleState.CANDIDATE, 0, ModelPromotionLifecycleState.CHALLENGER,
                        window, "submit-prod-challenger", "proposal-prod-challenger",
                        "Move exact production artifact to challenger", ModelPromotionDecisionRationale.sha256(
                                "Move exact production artifact to challenger")));
        assertThat(challengerProposal.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);

        authenticate(reviewerIssuer, reviewerSubject, Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
        var challengerIntent = reviewIntent("approval-prod-challenger", "review-prod-challenger",
                "Approve challenger state after independent production review");
        var challengerChallenge = signedReview.start(reviewer, "proposal-prod-challenger", challengerIntent);
        String challengerAssertion = signedAssertion(mongo, challengerChallenge.challengeId(), credentialId,
                userHandle, assertionKey, 1);
        assertThat(signedReview.finish(reviewer, "proposal-prod-challenger",
                new ModelPromotionSignedReviewService.SignedReview(challengerIntent,
                        challengerChallenge.challengeId(), challengerAssertion)).reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.APPROVED);
        assertThat(promotionStore.currentState(identity, governanceScope, intendedUse).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHALLENGER);

        authenticate(proposerIssuer, proposerSubject, Set.of(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
        ModelPromotionProposalResult shadowProposal = workflow.proposeTransition(
                new ModelPromotionWorkflowService.TransitionProposalRequest(identity, governanceScope, intendedUse,
                        ModelPromotionLifecycleState.CHALLENGER, 1, ModelPromotionLifecycleState.SHADOW_APPROVED,
                        window, "submit-prod-shadow", "proposal-prod-shadow",
                        "Request exact-evidence shadow approval", ModelPromotionDecisionRationale.sha256(
                                "Request exact-evidence shadow approval")));
        assertThat(shadowProposal.policySha256()).isEqualTo(prepared.policy().canonicalSha256());
        assertThat(shadowProposal.assessmentSha256()).isNotBlank();
        ModelPromotionProposalDocument pendingShadow = promotionStore.findProposal("proposal-prod-shadow")
                .orElseThrow();
        assertThat(pendingShadow.transition().artifactIdentity()).isEqualTo(identity);
        assertThat(pendingShadow.transition().governanceScope()).isEqualTo(governanceScope);
        assertThat(pendingShadow.transition().requestedTargetState())
                .isEqualTo(ModelPromotionLifecycleState.SHADOW_APPROVED);
        assertThat(pendingShadow.transition().expectedRevision()).isEqualTo(1);
        assertThat(pendingShadow.assessmentSnapshot().content().evidence().evidenceBundleSha256())
                .isEqualTo(evidence.canonicalSha256());

        authenticate(reviewerIssuer, reviewerSubject, Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
        var shadowIntent = reviewIntent("approval-prod-shadow", "review-prod-shadow",
                "Approve shadow state against verified production policy and exact evidence");
        var shadowChallenge = signedReview.start(reviewer, "proposal-prod-shadow", shadowIntent);
        String shadowAssertion = signedAssertion(mongo, shadowChallenge.challengeId(), credentialId,
                userHandle, assertionKey, 2);
        AtomicBoolean unknownCommitSignalled = new AtomicBoolean();
        MongoModelPromotionStore uncertainStore = modelPromotionStore(
                mongo, transactionManager, decisionValidator, unknownCommitSignalled);
        ModelPromotionWorkflowService uncertainWorkflow = new ModelPromotionWorkflowService(uncertainStore,
                evidenceResolver, assessmentService, policyResolver, authorization,
                Clock.fixed(decisionTime, ZoneOffset.UTC));
        ModelPromotionSignedReviewService uncertainSignedReview = new ModelPromotionSignedReviewService(
                uncertainStore, authorization, uncertainWorkflow, webAuthn, mongo, mapper);
        ModelPromotionProposalResult committed = uncertainSignedReview.finish(reviewer, "proposal-prod-shadow",
                new ModelPromotionSignedReviewService.SignedReview(shadowIntent,
                        shadowChallenge.challengeId(), shadowAssertion));
        assertThat(unknownCommitSignalled.get()).isTrue();
        assertThat(committed.idempotentReplay()).isTrue();

        ModelPromotionProposalDocument approved = promotionStore.findProposal("proposal-prod-shadow").orElseThrow();
        assertThat(approved.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.APPROVED);
        assertThat(approved.proposerIdentity()).isEqualTo(
                ModelPromotionActorIdentity.institutional(proposerIssuer, proposerSubject));
        assertThat(approved.reviewerIdentity()).isEqualTo(
                ModelPromotionActorIdentity.institutional(reviewerIssuer, reviewerSubject));
        assertThat(approved.approval().approvedBy()).isEqualTo(reviewerId);
        assertThat(approved.reviewAudit().actorId()).isEqualTo(reviewerId);
        ModelPromotionAggregate promoted = promotionStore.currentState(identity, governanceScope, intendedUse)
                .orElseThrow();
        assertThat(promoted.lifecycleState()).isEqualTo(ModelPromotionLifecycleState.SHADOW_APPROVED);
        assertThat(promoted.revision()).isEqualTo(2);
        assertThat(promotionStore.history(identity, governanceScope, intendedUse, 10)).hasSize(3);

        GovernanceWebAuthnBoundChallenge consumed = mongo.findById(shadowChallenge.challengeId(),
                GovernanceWebAuthnBoundChallenge.class);
        GovernanceWebAuthnBoundChallenge firstConsumed = mongo.findById(challengerChallenge.challengeId(),
                GovernanceWebAuthnBoundChallenge.class);
        PolicyWebAuthnCredential updatedCredential = mongo.findById(credentialId, PolicyWebAuthnCredential.class);
        GovernanceBoundWebAuthnProof proof = mongo.findById(shadowChallenge.challengeId(),
                GovernanceBoundWebAuthnProof.class);
        ModelPromotionWebAuthnReviewEvidence reviewEvidence = mongo.findById(shadowChallenge.challengeId(),
                ModelPromotionWebAuthnReviewEvidence.class);
        assertThat(consumed.used()).isTrue();
        assertThat(firstConsumed.used()).isTrue();
        assertThat(updatedCredential.signatureCount()).isEqualTo(2);
        assertThat(proof).isNotNull();
        assertThat(proof.previousSignatureCount()).isEqualTo(1);
        assertThat(proof.signatureCount()).isEqualTo(2);
        assertThat(proof.actorId()).isEqualTo(reviewerId);
        assertThat(proof.issuer()).isEqualTo(reviewerIssuer);
        assertThat(proof.subject()).isEqualTo(reviewerSubject);
        assertThat(proof.rpId()).isEqualTo("localhost");
        assertThat(proof.origin()).isEqualTo("http://localhost:4173");
        assertThat(proof.userPresenceRequired()).isTrue();
        assertThat(proof.userVerificationRequired()).isTrue();
        assertThat(reviewEvidence.assertionSha256()).isEqualTo(proof.assertionSha256());
        assertThat(reviewEvidence.contractSha256()).isEqualTo(proof.contractSha256());
        assertThat(mongo.count(Query.query(Criteria.where("namespace").is("model-promotion-review")),
                GovernanceBoundWebAuthnProof.class)).isEqualTo(2);
        assertThat(mongo.count(new Query(), ModelPromotionWebAuthnReviewEvidence.class)).isEqualTo(2);

        ObjectNode contract = (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(proof.contractJson());
        assertThat(contract.path("environment").asText()).isEqualTo("PRODUCTION");
        assertThat(contract.path("requestedTargetState").asText()).isEqualTo("SHADOW_APPROVED");
        assertThat(contract.path("expectedRevision").asLong()).isEqualTo(1);
        assertThat(contract.path("currentRevision").asLong()).isEqualTo(1);
        assertThat(contract.path("currentState").asText()).isEqualTo("CHALLENGER");
        assertThat(contract.path("modelArtifactSha256").asText()).isEqualTo(identity.modelArtifactSha256());
        assertThat(contract.path("policySha256").asText()).isEqualTo(prepared.policy().canonicalSha256());
        assertThat(contract.path("assessmentSha256").asText())
                .isEqualTo(approved.assessmentSnapshot().canonicalSha256());
        ModelPromotionDecisionDocument shadowDecision = promotionStore.history(
                identity, governanceScope, intendedUse, 10).stream()
                .filter(decision -> "review-prod-shadow".equals(decision.operationId()))
                .findFirst().orElseThrow();
        assertThat(shadowDecision.policySnapshot()).isEqualTo(prepared.policy());
        assertThat(shadowDecision.assessmentSnapshot()).isEqualTo(approved.assessmentSnapshot());
        assertThat(shadowDecision.localAudit().outcome()).isEqualTo("SUCCEEDED");
        assertThat(shadowDecision.localAudit().actorId()).isEqualTo(reviewerId);
        assertThat(shadowDecision.localAudit().action()).isEqualTo("ACCEPT_MODEL_PROMOTION_TRANSITION");
        assertThat(approved.reviewAudit().actorId()).isEqualTo(shadowDecision.localAudit().actorId());
        assertThat(mongo.count(new Query(), ModelPromotionDecisionDocument.class)).isEqualTo(3);
        var receipt = uncertainSignedReview.receipt(reviewer, "proposal-prod-shadow", "review-prod-shadow");
        assertThat(receipt.approvalId()).isEqualTo(shadowIntent.approvalId());
        assertThat(receipt.outcome()).isEqualTo(ModelPromotionApproval.Outcome.APPROVED);
        assertThat(receipt.rationaleSha256()).isEqualTo(shadowIntent.rationaleSha256());

        SimpleMongoClientDatabaseFactory reopenedFactory = new SimpleMongoClientDatabaseFactory(
                MONGO.getReplicaSetUrl(mongo.getDb().getName()));
        try {
            MongoTemplate reopenedMongo = new MongoTemplate(reopenedFactory);
            ModelPromotionPolicyFullTrustVerifier reopenedTrust = fullTrust(reopenedMongo);
            ModelPromotionPolicyResolver reopenedPolicyResolver = new ModelPromotionPolicyResolver(
                    new MongoModelPromotionPolicyStore(reopenedMongo),
                    new ModelPromotionPolicyValidator(mapper), reopenedTrust);
            MongoTransactionManager reopenedManager = new MongoTransactionManager(reopenedFactory);
            ModelPromotionDecisionValidator reopenedDecisionValidator = new ModelPromotionDecisionValidator(
                    mapper, new ModelPromotionPolicyValidator(mapper), reopenedTrust);
            MongoModelPromotionStore reopenedStore = modelPromotionStore(
                    reopenedMongo, reopenedManager, reopenedDecisionValidator, null);
            ModelPromotionWorkflowService reopenedWorkflow = new ModelPromotionWorkflowService(reopenedStore,
                    evidenceResolver, new ModelPromotionAssessmentService(reopenedPolicyResolver, mapper),
                    reopenedPolicyResolver, authorization, Clock.fixed(decisionTime, ZoneOffset.UTC));
            ModelPromotionSignedReviewService reopenedSignedReview = new ModelPromotionSignedReviewService(
                    reopenedStore, authorization, reopenedWorkflow,
                    policyWebAuthn(reopenedMongo, reopenedManager,
                            new MongoPolicyGovernanceStore(reopenedMongo, reopenedManager)),
                    reopenedMongo, mapper);
            PolicyGovernanceActor auditor = new PolicyGovernanceActor(
                    PolicyGovernanceActor.identityId("https://institution.example", "production-auditor"),
                    Set.of(ModelPolicyAuthority.AUDIT_READ, ModelPolicyAuthority.PRODUCTION),
                    "https://institution.example", "production-auditor");
            reopenedSignedReview.verifyHistoricalReview(auditor, "proposal-prod-shadow");
            reopenedSignedReview.verifyHistoricalReview(auditor, "proposal-prod-challenger");
            assertThat(reopenedSignedReview.receipt(reviewer, "proposal-prod-shadow", "review-prod-shadow"))
                    .isEqualTo(receipt);
            PolicyGovernanceActor otherReviewer = new PolicyGovernanceActor(
                    PolicyGovernanceActor.identityId(reviewerIssuer, "different-reviewer"),
                    reviewer.authorities(), reviewerIssuer, "different-reviewer");
            assertThatThrownBy(() -> reopenedSignedReview.receipt(
                    otherReviewer, "proposal-prod-shadow", "review-prod-shadow"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(reopenedStore.currentState(identity, governanceScope, intendedUse).orElseThrow())
                    .isEqualTo(promoted);
            assertThat(reopenedMongo.count(new Query(), ModelPromotionDecisionDocument.class)).isEqualTo(3);
            assertThat(reopenedMongo.findById(shadowChallenge.challengeId(), GovernanceBoundWebAuthnProof.class))
                    .isEqualTo(proof);
            assertThat(reopenedMongo.findById(credentialId, PolicyWebAuthnCredential.class).signatureCount())
                    .isEqualTo(2);
        } finally {
            reopenedFactory.destroy();
        }
    }

    @Test
    void approvedPolicyPublishesAndResolverRetrievesRealVerifiedPolicy() throws Exception {
        ModelPromotionPolicyPublisher.Draft proposal = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ModelPromotionPolicyPublisher publisher = publisher(approval(proposal), store);

        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher.prepare(
                proposal, issuerKey.getPrivate(), ISSUER, KEY_ID);
        assertThat(prepared.policy().content()).isEqualTo(proposal.content().approvedContent(
                Instant.parse("2026-05-01T00:00:00Z")));
        assertThat(prepared.policy().canonicalSha256()).isNotEqualTo(proposal.canonicalSha256());
        assertThat(verifier.verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
                ModelPromotionPolicyDocument.class)).isZero();
        ModelPromotionPolicyPublicationReceipt receipt = publisher.publishPrepared(prepared);

        assertThat(receipt.policyId()).isEqualTo("policy-1");
        assertThat(receipt.revision()).isEqualTo(1);
        assertThat(receipt.canonicalSha256()).isEqualTo(prepared.policy().canonicalSha256());
        assertThat(receipt.issuerIdentity()).isEqualTo(ISSUER);
        assertThat(receipt.keyId()).isEqualTo(KEY_ID);
        assertThat(receipt.publishedAt()).isEqualTo(CLOCK.instant());
        assertThat(receipt.alreadyPublished()).isFalse();
        ModelPromotionPolicyResolver resolver = new ModelPromotionPolicyResolver(
                new MongoModelPromotionPolicyStore(mongo), new ModelPromotionPolicyValidator(mapper), fullTrust());
        ModelPromotionPolicyResolution resolved = resolver.resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(resolved.status()).isEqualTo(ModelPromotionPolicyResolution.Status.AVAILABLE);
        assertThat(resolved.policy()).isEqualTo(prepared.policy());
    }

    @Test
    void validIssuerSignatureNeverSubstitutesForMissingOrChangedIndependentApproval() throws Exception {
        ModelPromotionPolicyPublisher.Draft draft = proposal("policy-chain-check", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ModelPromotionPolicyPublisher publisher = publisher(approval(draft), store);
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher.prepare(
                draft, issuerKey.getPrivate(), ISSUER, KEY_ID);
        publisher.publishPrepared(prepared);
        assertThat(verifier.verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        String key = ModelPromotionPolicyDocument.policyKey(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                draft.content().policyId(), draft.content().revision());
        var collection = mongo.getCollection(InternalPolicyApprovalEvidenceDocument.COLLECTION);
        org.bson.Document filter = new org.bson.Document("_id", key);
        org.bson.Document original = collection.find(filter).first();
        assertThat(original).isNotNull();
        collection.deleteOne(filter);
        assertThat(fullTrust().verify(prepared.policy()).approvalEvidence().reason())
                .isEqualTo(ModelPromotionPolicyApprovalEvidenceVerification.Reason.EVIDENCE_MISSING);
        collection.insertOne(original);

        List<String> changedFields = List.of(
                "approvalProof.approverSignature",
                "approvalProof.authorStatement.policyId",
                "approvalProof.authorStatement.revision",
                "approvalProof.authorStatement.governanceScope.environment",
                "approvalProof.approvalDecision.decidedAt");
        List<Object> changedValues = List.of("tampered", "another-policy", 2L, "TEST",
                "2026-05-02T00:00:00Z");
        for (int index = 0; index < changedFields.size(); index++) {
            collection.updateOne(filter, new org.bson.Document("$set",
                    new org.bson.Document(changedFields.get(index), changedValues.get(index))));
            assertThat(fullTrust().verify(prepared.policy()).status())
                    .as(changedFields.get(index))
                    .isEqualTo(ModelPromotionPolicyTrustVerification.Status.UNVERIFIED);
            collection.replaceOne(filter, original);
        }
        assertThat(fullTrust().verify(prepared.policy()).status())
                .isEqualTo(ModelPromotionPolicyTrustVerification.Status.FULLY_AUTHORIZED);
    }

    @Test
    void currentTrustRevocationAndUnavailableApprovalRegistryBlockNewUseWithoutChangingHistory() throws Exception {
        ModelPromotionPolicyPublisher.Draft draft = proposal("policy-trust-rotation", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ModelPromotionPolicyPublisher publisher = publisher(approval(draft), store);
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher.prepare(
                draft, issuerKey.getPrivate(), ISSUER, KEY_ID);
        publisher.publishPrepared(prepared);
        ModelPromotionPolicy policy = prepared.policy();
        assertThat(fullTrust().verify(policy).status())
                .isEqualTo(ModelPromotionPolicyTrustVerification.Status.FULLY_AUTHORIZED);

        var revokedApprover = new ModelPromotionPolicyFullTrustVerifier(verifier,
                new MongoModelPromotionPolicyApprovalEvidenceVerifier(mongo, mapper, CLOCK,
                        firstPartyTrust(ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED), null));
        assertThat(revokedApprover.verify(policy).status())
                .isEqualTo(ModelPromotionPolicyTrustVerification.Status.UNVERIFIED);
        var missingApprovalRegistry = new ModelPromotionPolicyFullTrustVerifier(verifier,
                ignored -> ModelPromotionPolicyApprovalEvidenceVerification.unavailable(
                        ModelPromotionPolicyApprovalEvidenceVerification.Reason.TRUST_UNAVAILABLE));
        assertThat(missingApprovalRegistry.verify(policy).approvalEvidence().reason())
                .isEqualTo(ModelPromotionPolicyApprovalEvidenceVerification.Reason.TRUST_UNAVAILABLE);

        var revokedIssuer = verifier(issuerKey, ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED);
        assertThat(revokedIssuer.verify(policy).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.REJECTED);
        assertThat(revokedIssuer.inspectHistoricalIntegrity(policy).status())
                .isEqualTo(ModelPromotionPolicyHistoricalIntegrity.Status.INTACT_REVOKED_KEY);
        assertThat(mongo.findById(ModelPromotionPolicyDocument.policyKey(scope(),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, draft.content().policyId(), 1),
                ModelPromotionPolicyDocument.class).policy()).isEqualTo(policy);
    }

    @Test
    void firstPartyProofAndPolicyCommitTogetherAndRemainAuditableAfterRestart() throws Exception {
        ModelPromotionPolicyPublisher.Draft proposal = proposal("policy-first-party", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        InternalPolicyApprovalArtifact proof = firstPartyProof(proposal);
        InternalPolicyPublicationAuthorization approval = new InternalPolicyPublicationAuthorization(
                mapper, firstPartyTrust(), proof, publisherKey.getPrivate(), CLOCK);
        ModelPromotionPolicy.Content approvedContent = proposal.content().approvedContent(
                Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(approval.authorize(approvedContent,
                new ModelPromotionPolicyValidator(mapper).canonicalSha256(approvedContent))).isPresent();
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
        ModelPromotionPolicyPublisher.Draft proposal = proposal("policy-rollback", 1,
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
        ModelPromotionPolicyPublisher.Draft proposal = proposal("policy-1", 1,
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
        ModelPromotionPolicyPublisher.Draft first = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        publisher(approval(first), store).publish(first, issuerKey.getPrivate(), ISSUER, KEY_ID);
        ModelPromotionPolicyPublisher.Draft changedRevision = proposal("policy-1", 1,
                "2026-10-01T00:00:00Z", "2026-12-01T00:00:00Z");
        ModelPromotionPolicyPublisher.Draft overlap = proposal("policy-2", 1,
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
        ModelPromotionPolicyPublisher.Draft first = proposal("policy-1", 1,
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ModelPromotionPolicyPublisher.Draft second = proposal("policy-2", 1,
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
        ModelPromotionPolicyPublisher.Draft proposal = proposal("policy-1", 1,
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
        ModelPromotionPolicyPublisher.Draft modified = proposal("policy-1", 2,
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
        ModelPromotionPolicyPublisher.Draft first = proposal("policy-1", 1,
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
            ModelPromotionPolicyPublisher.Draft overlap = proposal("policy-2", 1,
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

    private MongoModelPromotionStore modelPromotionStore(MongoTemplate template,
            MongoTransactionManager manager, ModelPromotionDecisionValidator decisionValidator,
            AtomicBoolean unknownCommitSignalled) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> managerProvider = mock(ObjectProvider.class);
        when(managerProvider.getIfAvailable()).thenReturn(manager);
        RegulatedMutationTransactionRunner transactionRunner = unknownCommitSignalled == null
                ? new RegulatedMutationTransactionRunner("REQUIRED", managerProvider)
                : new RegulatedMutationTransactionRunner("REQUIRED", managerProvider) {
                    @Override
                    public <T> T runLocalCommit(Supplier<T> callback) {
                        if (!unknownCommitSignalled.compareAndSet(false, true)) {
                            throw new AssertionError("Unknown commit must not replay the protected mutation");
                        }
                        super.runLocalCommit(callback);
                        MongoException unknown = new MongoException(91, "injected unknown commit result");
                        unknown.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
                        throw unknown;
                    }
                };
        return new MongoModelPromotionStore(template, transactionRunner, decisionValidator,
                new ModelPromotionLocalAuditFactory(),
                Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC));
    }

    private PolicyWebAuthnService policyWebAuthn(MongoTemplate template, MongoTransactionManager manager,
            MongoPolicyGovernanceStore governanceStore) {
        return new PolicyWebAuthnService(template, manager, new PolicyWebAuthnCredentialRepository(template), null,
                governanceStore, null, mapper, new MockEnvironment(), "localhost", "http://localhost:4173");
    }

    private ModelPromotionSignedReviewService.ReviewIntent reviewIntent(String approvalId, String operationId,
            String rationale) {
        return new ModelPromotionSignedReviewService.ReviewIntent(approvalId,
                ModelPromotionApproval.Outcome.APPROVED, operationId, rationale,
                ModelPromotionDecisionRationale.sha256(rationale));
    }

    private String signedAssertion(MongoTemplate template, String challengeId, String credentialId,
            String userHandle, KeyPair assertionKey, int counter) throws Exception {
        GovernanceWebAuthnBoundChallenge challengeDocument = template.findById(
                challengeId, GovernanceWebAuthnBoundChallenge.class);
        String challenge = AssertionRequest.fromJson(challengeDocument.requestJson())
                .getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url();
        byte[] clientData = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of(
                "type", "webauthn.get", "challenge", challenge,
                "origin", "http://localhost:4173", "crossOrigin", false));
        byte[] authenticatorData = ByteBuffer.allocate(37)
                .put(MessageDigest.getInstance("SHA-256").digest("localhost".getBytes(StandardCharsets.UTF_8)))
                .put((byte) 0x05).putInt(counter).array();
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(assertionKey.getPrivate());
        signature.update(authenticatorData);
        signature.update(MessageDigest.getInstance("SHA-256").digest(clientData));
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "id", credentialId, "rawId", credentialId, "type", "public-key",
                "response", Map.of("clientDataJSON", encoder.encodeToString(clientData),
                        "authenticatorData", encoder.encodeToString(authenticatorData),
                        "signature", encoder.encodeToString(signature.sign()), "userHandle", userHandle),
                "clientExtensionResults", Map.of()));
    }

    private byte[] coseCoordinate(java.math.BigInteger value) {
        byte[] encoded = value.toByteArray();
        byte[] coordinate = new byte[32];
        System.arraycopy(encoded, Math.max(0, encoded.length - 32), coordinate,
                Math.max(0, 32 - encoded.length), Math.min(32, encoded.length));
        return coordinate;
    }

    private ModelPromotionPolicyPublisher.Draft productionReviewProposal(String policyId, long revision,
            String effectiveFrom, String effectiveTo) {
        List<ModelPromotionPolicy.CriterionRule> rules = List.of(
                criterionRule(ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE, Map.of(
                        "minimumEvaluatedRecords", new BigDecimal("100"),
                        "minimumPositiveLabels", new BigDecimal("10"),
                        "minimumNegativeLabels", new BigDecimal("10"))),
                criterionRule(ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET, Map.of(
                        "alertReviewBudget", new BigDecimal("100"),
                        "minimumMlPrecision", new BigDecimal("0.70"),
                        "minimumDeltaVsRules", BigDecimal.ZERO)),
                criterionRule(ModelPromotionCriterion.FALSE_POSITIVE_RATE,
                        Map.of("maximumRate", new BigDecimal("0.10"))),
                criterionRule(ModelPromotionCriterion.RULES_VS_ML_DISAGREEMENT,
                        Map.of("maximumRate", new BigDecimal("0.10"))),
                criterionRule(ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS, Map.of(
                        "maximumCriticalRegressions", BigDecimal.ZERO,
                        "minimumSegmentsEvaluated", new BigDecimal("5"))),
                criterionRule(ModelPromotionCriterion.EXACT_MODEL_LATENCY_STABILITY, Map.of(
                        "maximumMilliseconds", new BigDecimal("500"),
                        "minimumSampleSize", new BigDecimal("100"))),
                criterionRule(ModelPromotionCriterion.EXACT_MODEL_FALLBACK_RATE, Map.of(
                        "maximumRate", new BigDecimal("0.05"),
                        "minimumDenominator", new BigDecimal("100"))),
                criterionRule(ModelPromotionCriterion.COMPLETE_EVALUATION_PROVENANCE, Map.of()),
                criterionRule(ModelPromotionCriterion.EXACT_SHA_MODEL_CARD, Map.of()),
                criterionRule(ModelPromotionCriterion.IDENTITY_AND_LINEAGE_INTEGRITY,
                        Map.of("maximumCriticalLineageGaps", BigDecimal.ZERO)));
        ModelPromotionPolicyDraft content = new ModelPromotionPolicyDraft(ModelPromotionPolicyDraft.VERSION,
                policyId, revision, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                "policy-author", "risk-committee", "approval-" + policyId, "policy-publisher", "publisher-key-1",
                ISSUER, KEY_ID, effectiveFrom, effectiveTo, rules);
        return new ModelPromotionPolicyPublisher.Draft(content, content.canonicalSha256(mapper));
    }

    private ModelPromotionPolicy.CriterionRule criterionRule(ModelPromotionCriterion criterion,
            Map<String, BigDecimal> thresholds) {
        return new ModelPromotionPolicy.CriterionRule(criterion,
                ModelPromotionPolicy.CriterionApplicability.MANDATORY, thresholds, 604800,
                "VERIFIED_REPORTS_AND_MANIFESTS", criterion.evidenceSource(), criterion.metricSemantics(),
                criterion.populationDefinition(), criterion.denominatorDefinition(), null);
    }

    private ModelPromotionEvidenceBundle productionReviewEvidence(ExactModelArtifactIdentity identity,
            ModelGovernanceScope governanceScope, ModelPromotionEvaluationWindow window) {
        ModelPromotionEvidenceBundle.PopulationEvidence population = new ModelPromotionEvidenceBundle.PopulationEvidence(
                1000, 1000, 100, 900, false, "COMPLETE", "BOUNDED_SOURCE_NOT_TRUNCATED");
        ModelPromotionEvidenceBundle.EvaluationEvidence evaluation = new ModelPromotionEvidenceBundle.EvaluationEvidence(
                population,
                Map.of(100, new ModelPromotionEvidenceBundle.RankedMetricEvidence(true, 100, 100,
                        new BigDecimal("0.80"), null)),
                new ModelPromotionEvidenceBundle.FairPrecisionComparisonEvidence(true, 100, 100,
                        new BigDecimal("0.80"), new BigDecimal("0.70"), "matched-cohort-sha256", null),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(true, new BigDecimal("0.01"),
                        9, 900, "ANALYST_FEEDBACK_LABEL", null),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(true, new BigDecimal("0.05"),
                        50, 1000, "FEEDBACK_CREATED_AT", null),
                new ModelPromotionEvidenceBundle.SegmentRegressionEvidence(true, 5, 0,
                        "segment-contract-v1", null),
                new ModelPromotionEvidenceBundle.LatencyEvidence(false, null, null, null,
                        "EXACT_ARTIFACT_LATENCY_EVIDENCE_NOT_REQUIRED_FOR_SHADOW_APPROVAL"),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(false, null, null, null, null,
                        "EXACT_ARTIFACT_FALLBACK_EVIDENCE_NOT_REQUIRED_FOR_SHADOW_APPROVAL"),
                new ModelPromotionEvidenceBundle.LineageEvidence("EXCLUDE", 0, 0, 0, 0, 0));
        ModelPromotionEvidenceBundle.Content content = new ModelPromotionEvidenceBundle.Content(
                ModelPromotionEvidenceBundle.CURRENT_VERSION, identity, governanceScope,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, window,
                new ModelPromotionEvidenceBundle.ModelCardEvidence("b".repeat(64),
                        identity.modelArtifactSha256(), "2026-08-01T00:00:01Z", "1".repeat(64),
                        "VERIFIED_AGAINST_SOURCE_BYTES"),
                new ModelPromotionEvidenceBundle.ShadowPerformanceEvidence("c".repeat(64),
                        "2026-08-01T00:00:01Z", "1".repeat(64), population.returnedCohortStatus(),
                        population.sourcePopulationStatus(), population.recordsConsidered(),
                        population.recordsEvaluated(), false),
                evaluation,
                new ModelPromotionEvidenceBundle.EvidenceTrust("VERIFIED_REPORTS_AND_MANIFESTS",
                        "VERIFIED_AGAINST_SOURCE_BYTES", "NOT_PROVIDED", "NOT_CLAIMED"),
                List.of("SYNTHETIC_INTEGRATION_FIXTURE_FOR_SIGNED_REVIEW_FLOW"));
        return new ModelPromotionEvidenceBundle(content, GovernanceCanonicalJson.sha256(mapper, content));
    }

    private ModelPromotionPolicyPublisher publisher(InternalPolicyPublicationAuthorization approval,
            MongoModelPromotionPolicyPublicationStore storage) {
        return new ModelPromotionPolicyPublisher(mapper, new ModelPromotionPolicyValidator(mapper), approval,
                verifier, storage, CLOCK);
    }

    private Ed25519ModelPromotionPolicyAuthorityVerifier verifier(KeyPair pair) throws Exception {
        return verifier(pair, ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
    }

    private Ed25519ModelPromotionPolicyAuthorityVerifier verifier(KeyPair pair,
            ModelPromotionTrustedIssuerRegistry.KeyStatus status) throws Exception {
        byte[] publicKey = pair.getPublic().getEncoded();
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey key = new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, KEY_ID, Base64.getEncoder().encodeToString(publicKey),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey)),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION), List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                "2025-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                status);
        ModelPromotionTrustedIssuerRegistry registry = ModelPromotionTrustedIssuerRegistry.fromConfiguration(
                new ModelPromotionTrustedIssuerRegistry.Configuration(ModelPromotionTrustedIssuerRegistry.VERSION,
                        1, List.of(key)), 1);
        return new Ed25519ModelPromotionPolicyAuthorityVerifier(mapper, registry, CLOCK);
    }

    private InternalPolicyPublicationAuthorization approval(ModelPromotionPolicyPublisher.Draft proposal)
            throws Exception {
        return new InternalPolicyPublicationAuthorization(mapper, firstPartyTrust(), firstPartyProof(proposal),
                publisherKey.getPrivate(), CLOCK);
    }

    private ModelPromotionPolicyFullTrustVerifier fullTrust() throws Exception {
        return fullTrust(mongo);
    }

    private ModelPromotionPolicyFullTrustVerifier fullTrust(MongoTemplate template) throws Exception {
        return new ModelPromotionPolicyFullTrustVerifier(verifier,
                new MongoModelPromotionPolicyApprovalEvidenceVerifier(template, mapper, CLOCK,
                        firstPartyTrust(), null));
    }

    private InternalPolicyApprovalArtifact firstPartyProof(ModelPromotionPolicyPublisher.Draft proposal)
            throws Exception {
        ModelPromotionPolicyDraft content = proposal.content();
        InternalPolicyApprovalArtifact.AuthorStatement statement = new InternalPolicyApprovalArtifact.AuthorStatement(
                InternalPolicyApprovalArtifact.VERSION, InternalPolicyApprovalArtifact.AUTHOR_PURPOSE,
                content.policyId(), content.revision(), proposal.canonicalSha256(), content.governanceScope(),
                content.intendedUse(), content.effectiveFromInclusive(), content.effectiveToExclusive(),
                content.approvalReference(),
                "policy-author", "author-key-1", "policy-publisher", "publisher-key-1", ISSUER, KEY_ID,
                "2026-04-01T00:00:00Z");
        InternalPolicyApprovalArtifact.ApprovalDecision decision = new InternalPolicyApprovalArtifact.ApprovalDecision(
                InternalPolicyApprovalArtifact.VERSION, InternalPolicyApprovalArtifact.APPROVER_PURPOSE,
                InternalPolicyApprovalArtifact.authorStatementSha256(mapper, statement), "risk-committee",
                "approver-key-1", InternalPolicyApprovalArtifact.Decision.APPROVED,
                "2026-05-01T00:00:00Z", "2026-06-15T00:00:00Z",
                content.approvalReference());
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
        return firstPartyTrust(ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
    }

    private InternalPolicyApprovalTrustRegistry firstPartyTrust(
            ModelPromotionTrustedIssuerRegistry.KeyStatus approverStatus) throws Exception {
        return InternalPolicyApprovalTrustRegistry.fromConfiguration(
                new InternalPolicyApprovalTrustRegistry.Configuration(InternalPolicyApprovalTrustRegistry.VERSION,
                        1, List.of(
                        actor("policy-author", "author-key-1", authorKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR),
                        actor("risk-committee", "approver-key-1", approverKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER, approverStatus),
                        actor("policy-publisher", "publisher-key-1", publisherKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER),
                        actor(ISSUER, KEY_ID, issuerKey, InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER))), 1);
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey actor(String identity, String keyId, KeyPair pair,
            InternalPolicyApprovalTrustRegistry.Role role) throws Exception {
        return actor(identity, keyId, pair, role, ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey actor(String identity, String keyId, KeyPair pair,
            InternalPolicyApprovalTrustRegistry.Role role,
            ModelPromotionTrustedIssuerRegistry.KeyStatus status) throws Exception {
        byte[] encoded = pair.getPublic().getEncoded();
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(identity, keyId,
                Base64.getEncoder().encodeToString(encoded),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                List.of(role), List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION), List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                "2025-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                status);
    }

    private ModelPromotionPolicyPublisher.Draft proposal(String policyId, long revision,
            String effectiveFrom, String effectiveTo) {
        List<ModelPromotionPolicy.CriterionRule> rules = java.util.Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(java.util.stream.Collectors.toMap(
                                key -> key, key -> BigDecimal.ONE)),
                        86400, "VERIFIED", criterion.evidenceSource(), criterion.metricSemantics(),
                        criterion.populationDefinition(), criterion.denominatorDefinition(), null))
                .toList();
        ModelPromotionPolicyDraft content = new ModelPromotionPolicyDraft(ModelPromotionPolicyDraft.VERSION,
                policyId, revision, scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                "policy-author", "risk-committee", "approval-1", "policy-publisher", "publisher-key-1",
                ISSUER, KEY_ID,
                effectiveFrom, effectiveTo, rules);
        return new ModelPromotionPolicyPublisher.Draft(content, content.canonicalSha256(mapper));
    }

    private ModelGovernanceScope scope() {
        return new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                ModelGovernanceEnvironment.PRODUCTION);
    }

    private void authenticate(String actorId, Set<String> authorities) {
        authenticate("https://model-promotion.example", actorId, authorities);
    }

    private void authenticate(String issuer, String subject, Set<String> authorities) {
        String actorId = PolicyGovernanceActor.identityId(issuer, subject);
        AnalystPrincipal principal = new AnalystPrincipal(subject, Set.of(), authorities,
                issuer + "#" + subject, actorId);
        SecurityContextHolder.getContext().setAuthentication(
                new AnalystAuthenticationFactory().authenticated(principal));
    }
}
