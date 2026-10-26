package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.modelpolicy.GovernanceBoundWebAuthnProof;
import com.frauddetection.alert.governance.modelpolicy.MongoPolicyGovernanceStore;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnCredential;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnCredentialRepository;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionCapabilityProbe;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.mongodb.MongoException;
import com.upokecenter.cbor.CBORObject;
import com.yubico.webauthn.AssertionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("invariant-proof")
@Tag("integration")
class MongoModelPromotionStoreIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");
    private static final ModelPromotionEmergencyAssurancePolicy EMERGENCY_ASSURANCE =
            new ModelPromotionEmergencyAssurancePolicy("https://issuer.example",
                    "urn:fraud:emergency:high", 300);
    private static PolicyGovernanceActor.AuthenticationEvidence emergencyEvidence() {
        return new PolicyGovernanceActor.AuthenticationEvidence("urn:fraud:emergency:high", NOW);
    }
    private static final String AUTHORIZATION = "9".repeat(64);
    private static final String RATIONALE = "8".repeat(64);

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private String mongoUri;
    private MongoTemplate mongoTemplate;
    private MongoModelPromotionStore store;
    private ModelPromotionDecisionValidator validator;

    @BeforeEach
    void setUp() {
        String databaseName = "model_promotion_" + UUID.randomUUID().toString().replace("-", "");
        mongoUri = FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName);
        databaseFactory = new SimpleMongoClientDatabaseFactory(mongoUri);
        mongoTemplate = new MongoTemplate(databaseFactory);
        createRequiredIndexes();
        ObjectMapper objectMapper = new ObjectMapper();
        validator = new PersistenceFocusedValidator(objectMapper);
        store = store(new ModelPromotionLocalAuditFactory(), validator);
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    @Test
    void committedSignedReviewIsCryptographicallyVerifiableAfterServiceRestart() throws Exception {
        ExactModelArtifactIdentity identity = identity("8");
        ModelGovernanceScope scope = scope("TEST");
        store.registerCandidate(registration(identity, scope, "register-signed-review"));
        store.submitProposal(proposal(identity, scope, "propose-signed-review", "proposal-signed-review", "maker"));
        store.submitProposal(proposal(identity, scope, "propose-stale-review", "proposal-stale-review", "maker-2"));

        String issuer = "https://issuer.example";
        String subject = "reviewer";
        String actorId = PolicyGovernanceActor.identityId(issuer, subject);
        PolicyGovernanceActor reviewer = new PolicyGovernanceActor(actorId, Set.of(), issuer, subject);
        var keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var keyPair = keyPairGenerator.generateKeyPair();
        var publicKey = (ECPublicKey) keyPair.getPublic();
        String credentialId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("signed-review-key".getBytes(StandardCharsets.UTF_8));
        String userHandle = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.util.HexFormat.of().parseHex(actorId.substring(5)));
        CBORObject cose = CBORObject.NewMap().Add(1, 2).Add(3, -7).Add(-1, 1)
                .Add(-2, coseCoordinate(publicKey.getW().getAffineX()))
                .Add(-3, coseCoordinate(publicKey.getW().getAffineY()));
        mongoTemplate.insert(new PolicyWebAuthnCredential(credentialId, actorId, issuer, subject, userHandle,
                Base64.getUrlEncoder().withoutPadding().encodeToString(cose.EncodeToBytes()),
                0, false, NOW, NOW, "ACTIVE", "TEST", null, null, null, null));

        MongoTransactionManager manager = new MongoTransactionManager(databaseFactory);
        PolicyWebAuthnService webAuthn = new PolicyWebAuthnService(mongoTemplate, manager,
                new PolicyWebAuthnCredentialRepository(mongoTemplate), null,
                new MongoPolicyGovernanceStore(mongoTemplate, manager), null,
                new ObjectMapper(), new MockEnvironment(), "localhost", "http://localhost:4173");
        ModelPromotionAuthorization authorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        ModelPromotionWorkflowService workflow = new ModelPromotionWorkflowService(store,
                mock(ModelPromotionEvidenceResolver.class), mock(ModelPromotionAssessmentService.class),
                mock(ModelPromotionPolicyResolver.class), authorization, Clock.fixed(NOW, ZoneOffset.UTC));
        ModelPromotionSignedReviewService signed = new ModelPromotionSignedReviewService(store,
                authorization, workflow, webAuthn, mongoTemplate, new ObjectMapper());
        authenticate(subject, Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE,
                AnalystAuthority.MODEL_PROMOTION_REJECT,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST));
        var intent = new ModelPromotionSignedReviewService.ReviewIntent("approval-signed-review",
                ModelPromotionApproval.Outcome.APPROVED, "review-signed-review", RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE));
        var options = signed.start(reviewer, "proposal-signed-review", intent);
        String challenge = AssertionRequest.fromJson(mongoTemplate.findById(options.challengeId(),
                com.frauddetection.alert.governance.modelpolicy.GovernanceWebAuthnBoundChallenge.class)
                .requestJson()).getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url();
        byte[] clientData = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of(
                "type", "webauthn.get", "challenge", challenge,
                "origin", "http://localhost:4173", "crossOrigin", false));
        byte[] authenticatorData = ByteBuffer.allocate(37)
                .put(MessageDigest.getInstance("SHA-256").digest("localhost".getBytes(StandardCharsets.UTF_8)))
                .put((byte) 0x05).putInt(1).array();
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(authenticatorData);
        signature.update(MessageDigest.getInstance("SHA-256").digest(clientData));
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String assertion = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "id", credentialId, "rawId", credentialId, "type", "public-key",
                "response", Map.of("clientDataJSON", encoder.encodeToString(clientData),
                        "authenticatorData", encoder.encodeToString(authenticatorData),
                        "signature", encoder.encodeToString(signature.sign()), "userHandle", userHandle),
                "clientExtensionResults", Map.of()));
        signed.finish(reviewer, "proposal-signed-review",
                new ModelPromotionSignedReviewService.SignedReview(intent, options.challengeId(), assertion));
        assertThat(mongoTemplate.findById(options.challengeId(), GovernanceBoundWebAuthnProof.class)).isNotNull();
        ModelPromotionProposalDocument signedProposal = store.findProposal("proposal-signed-review").orElseThrow();
        String makerId = PolicyGovernanceActor.identityId(issuer, "maker");
        assertThat(signedProposal.proposerIdentity().actorId()).isEqualTo(makerId);
        assertThat(signedProposal.proposal().proposedBy()).isEqualTo(makerId);
        assertThat(signedProposal.reviewerIdentity().actorId()).isEqualTo(actorId);
        assertThat(signedProposal.approval().approvedBy()).isEqualTo(actorId);
        assertThat(signedProposal.submissionAudit().actorId()).isEqualTo(makerId);
        assertThat(signedProposal.reviewAudit().actorId()).isEqualTo(actorId);
        ModelPromotionDecisionDocument signedDecision = store.history(identity, scope, use(), 10).stream()
                .filter(decision -> "review-signed-review".equals(decision.operationId()))
                .findFirst().orElseThrow();
        assertThat(signedDecision.actorId()).isEqualTo(actorId);
        assertThat(signedDecision.actorIdentity()).isEqualTo(signedProposal.reviewerIdentity());

        var staleIntent = new ModelPromotionSignedReviewService.ReviewIntent("approval-stale-review",
                ModelPromotionApproval.Outcome.REJECTED, "review-stale-review", RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE));
        var staleOptions = signed.start(reviewer, "proposal-stale-review", staleIntent);
        String staleChallenge = AssertionRequest.fromJson(mongoTemplate.findById(staleOptions.challengeId(),
                com.frauddetection.alert.governance.modelpolicy.GovernanceWebAuthnBoundChallenge.class)
                .requestJson()).getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url();
        byte[] staleClientData = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of(
                "type", "webauthn.get", "challenge", staleChallenge,
                "origin", "http://localhost:4173", "crossOrigin", false));
        byte[] staleAuthenticatorData = ByteBuffer.allocate(37)
                .put(MessageDigest.getInstance("SHA-256").digest("localhost".getBytes(StandardCharsets.UTF_8)))
                .put((byte) 0x05).putInt(2).array();
        Signature staleSignature = Signature.getInstance("SHA256withECDSA");
        staleSignature.initSign(keyPair.getPrivate());
        staleSignature.update(staleAuthenticatorData);
        staleSignature.update(MessageDigest.getInstance("SHA-256").digest(staleClientData));
        String staleAssertion = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "id", credentialId, "rawId", credentialId, "type", "public-key",
                "response", Map.of("clientDataJSON", encoder.encodeToString(staleClientData),
                        "authenticatorData", encoder.encodeToString(staleAuthenticatorData),
                        "signature", encoder.encodeToString(staleSignature.sign()), "userHandle", userHandle),
                "clientExtensionResults", Map.of()));
        AtomicBoolean unknownCommitSignalled = new AtomicBoolean();
        MongoModelPromotionStore uncertainStore = storeWithCommitSignal(unknownCommitSignalled, true);
        ModelPromotionWorkflowService uncertainWorkflow = new ModelPromotionWorkflowService(uncertainStore,
                mock(ModelPromotionEvidenceResolver.class), mock(ModelPromotionAssessmentService.class),
                mock(ModelPromotionPolicyResolver.class), authorization, Clock.fixed(NOW, ZoneOffset.UTC));
        ModelPromotionSignedReviewService uncertainSigned = new ModelPromotionSignedReviewService(uncertainStore,
                authorization, uncertainWorkflow, webAuthn, mongoTemplate, new ObjectMapper());
        assertThat(uncertainSigned.finish(reviewer, "proposal-stale-review",
                new ModelPromotionSignedReviewService.SignedReview(staleIntent, staleOptions.challengeId(),
                        staleAssertion)).idempotentReplay()).isTrue();
        assertThat(unknownCommitSignalled.get()).isTrue();

        var reopenedFactory = new SimpleMongoClientDatabaseFactory(mongoUri);
        try {
            MongoTemplate reopenedMongo = new MongoTemplate(reopenedFactory);
            MongoModelPromotionStore reopenedStore = new MongoModelPromotionStore(reopenedMongo,
                    requiredTransactionRunner(), validator, new ModelPromotionLocalAuditFactory(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            ModelPromotionSignedReviewService restarted = new ModelPromotionSignedReviewService(reopenedStore,
                    authorization, workflow, webAuthn, reopenedMongo, new ObjectMapper());
            assertThat(restarted.finish(reviewer, "proposal-signed-review",
                    new ModelPromotionSignedReviewService.SignedReview(intent, options.challengeId(), assertion))
                    .idempotentReplay()).isTrue();
            PolicyGovernanceActor auditor = new PolicyGovernanceActor(
                    PolicyGovernanceActor.identityId(issuer, "auditor"),
                    Set.of(ModelPolicyAuthority.AUDIT_READ), issuer, "auditor");
            restarted.verifyHistoricalReview(auditor, "proposal-signed-review");
            restarted.verifyHistoricalReview(auditor, "proposal-stale-review");
            assertThatThrownBy(() -> restarted.verifyHistoricalReview(reviewer, "proposal-signed-review"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            reopenedMongo.updateFirst(Query.query(Criteria.where("_id").is("proposal-signed-review")),
                    new Update().set("transition.artifactIdentity.modelArtifactSha256", "0".repeat(64)),
                    ModelPromotionProposalDocument.class);
            assertThatThrownBy(() -> restarted.verifyHistoricalReview(auditor, "proposal-signed-review"))
                    .isInstanceOf(SecurityException.class);
        } finally {
            reopenedFactory.destroy();
        }
    }

    @Test
    void legacyPendingProposalWithoutIssuerBindingMustBeResubmitted() {
        var submission = proposal(identity("9"), scope("TEST"), "legacy-proposal-operation",
                "legacy-proposal", "legacy-maker");
        var current = ModelPromotionProposalDocument.pending(
                ModelPromotionPersistenceKey.aggregate(identity("9"), scope("TEST"), use()),
                "a".repeat(64), submission,
                new ModelPromotionDecisionDocument.LocalAuditEvidence("legacy-audit",
                        "SUBMIT_MODEL_PROMOTION_PROPOSAL", "SUCCEEDED", submission.operationContext().actorId(),
                        AUTHORIZATION, NOW),
                NOW);
        ModelPromotionProposalDocument legacy = new ModelPromotionProposalDocument(
                current.proposalId(), current.aggregateKey(), current.operationId(), current.commandSha256(),
                current.requestIntentSha256(), current.actorId(), current.authorizationFingerprint(),
                current.transition(), current.policySnapshot(), current.assessmentSnapshot(), current.proposal(),
                current.reviewStatus(), current.approval(), current.reviewOperationId(), current.reviewCommandSha256(),
                current.reviewerAuthorizationFingerprint(), current.submissionAudit(), current.reviewAudit(),
                current.submittedAt(), current.reviewedAt(), null, null);
        mongoTemplate.insert(legacy);

        assertThatThrownBy(() -> store.reviewProposal(review("legacy-proposal", "legacy-review-operation",
                "legacy-reviewer"), () -> { }))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("resubmit");
        assertThat(store.findProposal("legacy-proposal").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
    }

    private static byte[] coseCoordinate(java.math.BigInteger value) {
        byte[] encoded = value.toByteArray();
        byte[] out = new byte[32];
        System.arraycopy(encoded, Math.max(0, encoded.length - 32), out,
                Math.max(0, 32 - encoded.length), Math.min(32, encoded.length));
        return out;
    }

    @Test
    void registrationOperationIdCannotBeReusedForProposal() {
        ExactModelArtifactIdentity identity = identity("8");
        ModelGovernanceScope scope = scope("TEST");
        store.registerCandidate(registration(identity, scope, "shared-operation"));

        assertThatThrownBy(() -> store.submitProposal(proposal(
                identity, scope, "shared-operation", "proposal-shared", "proposer"
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("operation ID");
        assertThat(store.findProposal("proposal-shared")).isEmpty();
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(1);
    }

    @Test
    void proposalOperationIdCannotBeReusedForEitherReviewOutcome() {
        ExactModelArtifactIdentity identity = identity("8");
        ModelGovernanceScope scope = scope("TEST");
        store.registerCandidate(registration(identity, scope, "register-distinct"));
        store.submitProposal(proposal(identity, scope, "shared-operation", "proposal-shared", "proposer"));

        for (ModelPromotionApproval.Outcome outcome : ModelPromotionApproval.Outcome.values()) {
            assertThatThrownBy(() -> store.reviewProposal(
                    review("proposal-shared", "shared-operation", "independent-reviewer", outcome), () -> {
                    }
            )).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                    .hasMessageContaining("operation ID");
        }
        assertThat(store.findProposal("proposal-shared").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(2);
    }

    @Test
    void reviewOperationIdCannotBeReusedForAnotherProposal() {
        ExactModelArtifactIdentity identity = identity("8");
        ModelGovernanceScope scope = scope("TEST");
        store.registerCandidate(registration(identity, scope, "register-distinct"));
        store.submitProposal(proposal(identity, scope, "proposal-operation-a", "proposal-a", "proposer-a"));
        store.submitProposal(proposal(identity, scope, "proposal-operation-b", "proposal-b", "proposer-b"));
        store.reviewProposal(review("proposal-a", "shared-review", "reviewer",
                ModelPromotionApproval.Outcome.REJECTED), () -> {
        });

        assertThatThrownBy(() -> store.reviewProposal(review("proposal-b", "shared-review", "reviewer",
                ModelPromotionApproval.Outcome.REJECTED), () -> {
        })).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("operation ID");
        assertThat(store.findProposal("proposal-b").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
    }

    @Test
    void committedQualityProposalReplaysFromMongoBeforePolicyAndEvidenceAfterRestart() {
        ExactModelArtifactIdentity identity = ModelPromotionAssessmentTestFixtures.identity();
        ModelGovernanceScope scope = scope("TEST");
        store.registerCandidate(registration(identity, scope, "register-replay"));
        store.submitProposal(proposal(identity, scope, "propose-challenger", "proposal-challenger", "proposer"));
        store.reviewProposal(review("proposal-challenger", "approve-challenger", "reviewer"), () -> {
        });

        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence(scope);
        ModelPromotionPolicy policy = ModelPromotionAssessmentTestFixtures.policy(scope);
        ModelPromotionAssessment assessment = ModelPromotionAssessmentTestFixtures.service(
                (requestedScope, intendedUse, at) -> Optional.of(policy)
        ).assess(evidence, NOW);
        ModelPromotionEvidenceResolver evidenceResolver = mock(ModelPromotionEvidenceResolver.class);
        ModelPromotionAssessmentService assessmentService = mock(ModelPromotionAssessmentService.class);
        ModelPromotionPolicyResolver policyResolver = mock(ModelPromotionPolicyResolver.class);
        when(policyResolver.resolve(scope, use(), NOW)).thenReturn(ModelPromotionPolicyResolution.available(policy));
        when(evidenceResolver.resolve(identity, scope, use(), evidence.content().evaluationWindow()))
                .thenReturn(ModelPromotionEvidenceResolution.available(evidence));
        when(assessmentService.assess(evidence, NOW)).thenReturn(assessment);
        authenticate("proposer", Set.of(
                AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST
        ));
        ModelPromotionWorkflowService.TransitionProposalRequest request = new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity, scope, use(), ModelPromotionLifecycleState.CHALLENGER, 1,
                ModelPromotionLifecycleState.SHADOW_APPROVED, evidence.content().evaluationWindow(),
                "propose-shadow", "proposal-shadow", RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE)
        );
        ModelPromotionProposalResult committed = workflow(store, evidenceResolver, assessmentService, policyResolver)
                .proposeTransition(request);
        assertThat(committed.idempotentReplay()).isFalse();

        ModelPromotionEvidenceResolver unavailableEvidence = mock(ModelPromotionEvidenceResolver.class);
        ModelPromotionAssessmentService unavailableAssessment = mock(ModelPromotionAssessmentService.class);
        ModelPromotionPolicyResolver unavailablePolicy = mock(ModelPromotionPolicyResolver.class);
        MongoModelPromotionStore restarted = store(new ModelPromotionLocalAuditFactory(), validator);
        ModelPromotionWorkflowService recoveredWorkflow = workflow(
                restarted, unavailableEvidence, unavailableAssessment, unavailablePolicy
        );
        ModelPromotionProposalResult recovered = recoveredWorkflow.proposeTransition(request);
        assertThat(recovered.idempotentReplay()).isTrue();
        assertThat(recovered.proposalId()).isEqualTo(committed.proposalId());
        verifyNoInteractions(unavailablePolicy, unavailableEvidence, unavailableAssessment);

        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity, scope, use(), ModelPromotionLifecycleState.CHALLENGER, 1,
                ModelPromotionLifecycleState.SHADOW_APPROVED, evidence.content().evaluationWindow(),
                "propose-shadow", "proposal-shadow", "changed rationale",
                ModelPromotionDecisionRationale.sha256("changed rationale")
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class);
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity, scope, use(), ModelPromotionLifecycleState.CHALLENGER, 1,
                ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, evidence.content().evaluationWindow(),
                "propose-shadow", "proposal-shadow", RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE)
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class);
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity, scope, use(), ModelPromotionLifecycleState.CHALLENGER, 2,
                ModelPromotionLifecycleState.SHADOW_APPROVED, evidence.content().evaluationWindow(),
                "propose-shadow", "proposal-shadow", RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE)
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class);
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity, scope, use(), ModelPromotionLifecycleState.CHALLENGER, 1,
                ModelPromotionLifecycleState.SHADOW_APPROVED,
                new ModelPromotionEvaluationWindow("2026-06-02T00:00:00Z", "2026-06-09T23:59:59Z"),
                "propose-shadow", "proposal-shadow", RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE)
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class);

        authenticate("another-proposer", Set.of(
                AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST
        ));
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(request))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class);
        authenticate("proposer", Set.of(AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST));
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(request))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(error -> assertThat(((ModelPromotionWorkflowBlockedException) error).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.UNAUTHORIZED));
        assertThat(store.findProposal("proposal-shadow").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(store.proposals(identity, scope, use(), 10)).hasSize(2);

        authenticate("proposer", Set.of(
                AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST
        ));
        when(unavailablePolicy.resolve(scope, use(), NOW)).thenReturn(
                ModelPromotionPolicyResolution.unavailable(ModelPromotionPolicyResolution.Reason.POLICY_STORE_UNAVAILABLE)
        );
        ModelPromotionWorkflowService.TransitionProposalRequest newOperation = new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity, scope, use(), ModelPromotionLifecycleState.CHALLENGER, 1,
                ModelPromotionLifecycleState.SHADOW_APPROVED, evidence.content().evaluationWindow(),
                "propose-shadow-new", "proposal-shadow-new", RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE)
        );
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(newOperation))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(error -> assertThat(((ModelPromotionWorkflowBlockedException) error).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.POLICY_UNAVAILABLE));
        assertThat(store.findProposal("proposal-shadow-new")).isEmpty();

        when(unavailablePolicy.resolve(scope, use(), NOW)).thenReturn(ModelPromotionPolicyResolution.available(policy));
        when(unavailableEvidence.resolve(identity, scope, use(), evidence.content().evaluationWindow()))
                .thenReturn(ModelPromotionEvidenceResolution.notFound(
                        ModelPromotionEvidenceResolution.Reason.SHADOW_PERFORMANCE_NOT_FOUND
                ));
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(newOperation))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(error -> assertThat(((ModelPromotionWorkflowBlockedException) error).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.EVIDENCE_UNAVAILABLE));
        assertThat(store.findProposal("proposal-shadow-new")).isEmpty();
    }

    @Test
    void committedRegistrationAndReviewedProposalSurviveRestartAndReplayDeterministically() {
        ExactModelArtifactIdentity identity = identity("a");
        ModelPromotionMutationResult registration = store.registerCandidate(registration(identity, scope("PRODUCTION"), "register-a"));
        ModelPromotionProposalSubmission submission = proposal(
                identity, scope("PRODUCTION"), "challenger-a", "proposal-challenger-a", "proposer"
        );
        store.submitProposal(submission);
        ModelPromotionProposalReview approval = review("proposal-challenger-a", "review-challenger-a", "reviewer");
        ModelPromotionProposalResult committed = store.reviewProposal(approval, () -> {
        });

        MongoModelPromotionStore restarted = store(new ModelPromotionLocalAuditFactory(), validator);
        ModelPromotionProposalReview retriedAfterLostResponse = new ModelPromotionProposalReview(
                approval.proposalId(), approval.approval(),
                new ModelPromotionOperationContext(
                        approval.operationContext().operationId(),
                        approval.operationContext().actorId(),
                        approval.operationContext().authorizationFingerprint(),
                        NOW.plusSeconds(30),
                        approval.operationContext().actorIdentity()
                )
        );
        ModelPromotionProposalResult replay = restarted.reviewProposal(retriedAfterLostResponse, () -> {
            throw new AssertionError("Committed review must not revalidate mutable dependencies");
        });

        assertThat(registration.aggregate().lifecycleState()).isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(committed.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.proposalId()).isEqualTo(committed.proposalId());
        assertThat(restarted.currentState(identity, scope("PRODUCTION"), use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(restarted.history(identity, scope("PRODUCTION"), use(), 10)).hasSize(2);
    }

    @Test
    void modifiedDuplicateAndConcurrentStaleRevisionCannotOverwriteCommittedState() throws Exception {
        ExactModelArtifactIdentity identity = identity("b");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-b"));
        acceptTransition(identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHALLENGER, "shared-operation");
        ModelPromotionProposalSubmission modified = proposal(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.REJECTED, "shared-operation", "proposal-shared-operation", "proposer-shared-operation"
        );
        assertThatThrownBy(() -> store.submitProposal(modified))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("different canonical content");

        ModelPromotionProposalSubmission stale = proposal(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.REJECTED, "stale-operation", "proposal-stale-operation", "proposer-stale-operation"
        );
        assertThatThrownBy(() -> store.submitProposal(stale)).isInstanceOf(IllegalStateException.class);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
    }

    @Test
    void auditFailureRollsBackStateAndDecisionHistory() {
        ExactModelArtifactIdentity identity = identity("c");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-c"));
        store.submitProposal(proposal(identity, scope, "audit-failure", "proposal-audit-failure", "proposer"));
        MongoModelPromotionStore failing = store(new ModelPromotionLocalAuditFactory() {
            @Override
            public ModelPromotionDecisionDocument.LocalAuditEvidence accepted(
                    String action, ModelPromotionOperationContext context, Instant committedAt
            ) {
                if (action.startsWith("ACCEPT_")) {
                    throw new IllegalStateException("local audit unavailable");
                }
                return super.accepted(action, context, committedAt);
            }
        }, validator);

        assertThatThrownBy(() -> failing.reviewProposal(
                review("proposal-audit-failure", "review-audit-failure", "reviewer"), () -> {
                }
        )).hasMessageContaining("local audit unavailable");

        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
        assertThat(store.findProposal("proposal-audit-failure").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
    }

    @Test
    void databaseEnforcesSingleChampionDuringConcurrentTransitions() throws Exception {
        ModelGovernanceScope scope = scope("PRODUCTION");
        ExactModelArtifactIdentity first = identity("d");
        ExactModelArtifactIdentity second = identity("e");
        advanceToPromotionRecommended(first, scope, "first");
        advanceToPromotionRecommended(second, scope, "second");
        store.submitProposal(proposal(first, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-first", "proposal-champion-first", "proposer-first"));
        store.submitProposal(proposal(second, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-second", "proposal-champion-second", "proposer-second"));

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> reviewAfter(start, review("proposal-champion-first", "review-champion-first", "reviewer-first")),
                    () -> reviewAfter(start, review("proposal-champion-second", "review-champion-second", "reviewer-second"))
            );
            var futures = new ArrayList<>(attempts.stream().map(executor::submit).toList());
            start.countDown();
            long successes = 0;
            for (var future : futures) {
                if (future.get()) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(mongoTemplate.count(new Query(), ModelPromotionChampionClaimDocument.class)).isEqualTo(1);
        assertThat(List.of(
                store.currentState(first, scope, use()).orElseThrow().lifecycleState(),
                store.currentState(second, scope, use()).orElseThrow().lifecycleState()
        )).containsExactlyInAnyOrder(
                ModelPromotionLifecycleState.CHAMPION,
                ModelPromotionLifecycleState.PROMOTION_RECOMMENDED
        );
    }

    @Test
    void championClaimsAreIndependentAcrossAuthorizedEnvironments() {
        ExactModelArtifactIdentity production = identity("f");
        ExactModelArtifactIdentity test = identity("1");
        ModelGovernanceScope productionScope = scope("PRODUCTION");
        ModelGovernanceScope testScope = scope("TEST");
        advanceToPromotionRecommended(production, productionScope, "production");
        advanceToPromotionRecommended(test, testScope, "test");

        acceptTransition(production, productionScope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-production");
        acceptTransition(test, testScope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-test");

        assertThat(mongoTemplate.count(new Query(), ModelPromotionChampionClaimDocument.class)).isEqualTo(2);
    }

    @Test
    void invalidTransitionLeavesCurrentStateAndImmutableHistoryUnchanged() {
        ExactModelArtifactIdentity identity = identity("2");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-invalid"));

        assertThatThrownBy(() -> store.submitProposal(proposal(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHAMPION, "invalid-direct-champion",
                "proposal-invalid-direct-champion", "proposer"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported model promotion transition");

        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
    }

    @Test
    void databaseOutageCannotReturnSuccessOrMutateDurableState() throws Exception {
        ExactModelArtifactIdentity identity = identity("3");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-outage"));
        databaseFactory.destroy();
        databaseFactory = null;

        assertThatThrownBy(() -> store.submitProposal(proposal(
                identity, scope, "outage-transition", "proposal-outage-transition", "proposer"
        ))).isInstanceOf(RuntimeException.class);

        databaseFactory = new SimpleMongoClientDatabaseFactory(mongoUri);
        mongoTemplate = new MongoTemplate(databaseFactory);
        validator = new PersistenceFocusedValidator(new ObjectMapper());
        store = store(new ModelPromotionLocalAuditFactory(), validator);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
    }

    @Test
    void durableProposalRequiresAnIndependentReviewerBeforeChangingLifecycleState() {
        ExactModelArtifactIdentity identity = identity("4");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-proposal"));
        ModelPromotionProposalSubmission submitted = proposal(
                identity, scope, "proposal-operation", "proposal-1", "proposer"
        );
        ModelPromotionProposalResult pending = store.submitProposal(submitted);

        assertThat(pending.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThatThrownBy(() -> store.reviewProposal(
                review("proposal-1", "self-review", "proposer"), () -> {
                }
        ))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("cannot review their own proposal");
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CANDIDATE);

        ModelPromotionProposalResult approved = store.reviewProposal(
                review("proposal-1", "independent-review", "approver"), () -> {
                }
        );

        assertThat(approved.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.APPROVED);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(store.findProposal("proposal-1").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.APPROVED);
        ModelPromotionDecisionDocument acceptedDecision = store.history(identity, scope, use(), 10).stream()
                .filter(decision -> decision.eventType()
                        == ModelPromotionDecisionDocument.EventType.LIFECYCLE_TRANSITION_ACCEPTED)
                .findFirst()
                .orElseThrow();
        assertThat(acceptedDecision.proposal().rationale().content())
                .isEqualTo(RATIONALE);
        assertThat(acceptedDecision.approval().rationale().referenceId())
                .isEqualTo(ModelPromotionDecisionRationale.approvalReference("proposal-1", "approval-independent-review"));
        ModelPromotionProposalResult replay = store.reviewProposal(
                review("proposal-1", "independent-review", "approver"), () -> {
                    throw new AssertionError("Committed review must not revalidate mutable dependencies");
                }
        );
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(store.history(identity, scope, use(), 10).stream()
                .filter(decision -> decision.eventType()
                        == ModelPromotionDecisionDocument.EventType.LIFECYCLE_TRANSITION_ACCEPTED)).hasSize(1);
    }

    @Test
    void concurrentIdenticalProposalSubmissionCreatesOneDurableProposal() throws Exception {
        ExactModelArtifactIdentity identity = identity("9");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-identical"));
        ModelPromotionProposalSubmission submission = proposal(
                identity, scope, "proposal-identical", "proposal-identical", "proposer"
        );
        ModelPromotionProposalRequestIntent intent = new ModelPromotionProposalRequestIntent(
                submission.transition(),
                new ModelPromotionEvaluationWindow("2026-06-01T00:00:00Z", "2026-06-09T23:59:59Z"),
                submission.proposal().proposalId(),
                submission.operationContext().actorId(),
                submission.proposal().rationale()
        );
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<ModelPromotionProposalResult>> attempts = List.of(
                    () -> { start.await(); return store.submitProposal(submission, intent); },
                    () -> { start.await(); return store.submitProposal(submission, intent); }
            );
            var futures = attempts.stream().map(executor::submit).toList();
            start.countDown();
            assertThat(futures.get(0).get().proposalId()).isEqualTo("proposal-identical");
            assertThat(futures.get(1).get().proposalId()).isEqualTo("proposal-identical");
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.proposals(identity, scope, use(), 10)).hasSize(1);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(2);
    }

    @Test
    void concurrentDifferentProposalContentUnderOneOperationIdCommitsOnlyOne() throws Exception {
        ExactModelArtifactIdentity identity = identity("9");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-concurrent-content"));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> submitAfter(start, proposal(identity, scope, "shared-content-operation", "proposal-a", "proposer")),
                    () -> submitAfter(start, proposal(identity, scope, "shared-content-operation", "proposal-b", "proposer"))
            );
            var futures = attempts.stream().map(executor::submit).toList();
            start.countDown();
            assertThat((futures.get(0).get() ? 1 : 0) + (futures.get(1).get() ? 1 : 0)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.proposals(identity, scope, use(), 10)).hasSize(1);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(2);
    }

    @Test
    void independentlyAuthorizedEmergencyRetirementReleasesChampionWithoutPolicyEvidence() {
        ExactModelArtifactIdentity identity = identity("a");
        ModelGovernanceScope scope = scope("PRODUCTION");
        advanceToPromotionRecommended(identity, scope, "retire");
        acceptTransition(identity, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-retire");
        ModelPromotionProposalSubmission retirement = retirementProposal(
                identity, scope, "retirement-operation", "retirement-proposal", "retirement-proposer"
        );

        ModelPromotionProposalResult pending = store.submitProposal(retirement);
        String issuer = "https://issuer.example";
        String reviewer = "retirement-reviewer";
        PolicyGovernanceActor requester = new PolicyGovernanceActor(
                PolicyGovernanceActor.identityId(issuer, reviewer), Set.of(
                AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST,
                AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_RETIRE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION), issuer, reviewer,
                emergencyEvidence());
        PolicyGovernanceActor authorizer = new PolicyGovernanceActor(
                PolicyGovernanceActor.identityId(issuer, "incident-authorizer"), Set.of(
                AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION), issuer, "incident-authorizer",
                emergencyEvidence());
        ModelPromotionAuthorization reviewerAuthorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        ModelPromotionWorkflowService retirementWorkflow = new ModelPromotionWorkflowService(store,
                mock(ModelPromotionEvidenceResolver.class), mock(ModelPromotionAssessmentService.class),
                mock(ModelPromotionPolicyResolver.class), reviewerAuthorization, Clock.fixed(NOW, ZoneOffset.UTC));
        ModelPromotionEmergencyRetirementService emergency = new ModelPromotionEmergencyRetirementService(
                mongoTemplate, new TransactionTemplate(new MongoTransactionManager(databaseFactory)),
                store, retirementWorkflow, reviewerAuthorization, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), EMERGENCY_ASSURANCE);
        emergency.ensureIndexes();
        authenticate(reviewer, requester.authorities());
        var command = new ModelPromotionEmergencyRetirementService.Request("authorization-retire",
                "review-retire", "approval-retire", "case-12345", "incident-ledger:case-12345",
                "Immediate retirement required after an independently confirmed model incident");
        assertThatThrownBy(() -> emergency.request(requester, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("missing-evidence", "review-missing",
                        "approval-missing", "case-12345", "", command.reason())))
                .isInstanceOf(IllegalArgumentException.class);
        var requested = emergency.request(requester, pending.proposalId(), command);
        assertThat(requested.status()).isEqualTo("REQUESTED");
        assertThatThrownBy(() -> emergency.retire(requester, "another-model-proposal",
                requested.authorizationId(), "unused-token"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        String intentSha256 = emergency.inspect(authorizer, requested.authorizationId()).intentSha256();
        var fraudAdmin = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, "fraud-admin"),
                AnalystRole.FRAUD_OPS_ADMIN.authorities(), issuer, "fraud-admin");
        assertThatThrownBy(() -> emergency.authorize(fraudAdmin, requested.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        intentSha256, command.incidentEvidenceReference(),
                        "I independently verified incident evidence")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> emergency.authorize(requester, requested.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        intentSha256, command.incidentEvidenceReference(),
                        "I independently verified incident evidence")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> emergency.authorize(authorizer, requested.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        "0".repeat(64), command.incidentEvidenceReference(),
                        "I independently verified incident evidence")))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class);
        assertThatThrownBy(() -> emergency.authorize(authorizer, requested.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        intentSha256, "incident-ledger:different-case",
                        "I independently verified incident evidence")))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class);
        emergency.authorize(authorizer, requested.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        intentSha256, command.incidentEvidenceReference(),
                        "I independently verified the incident record"));
        assertThat(emergency.authorize(authorizer, requested.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        intentSha256, command.incidentEvidenceReference(),
                        "I independently verified the incident record")).status()).isEqualTo("AUTHORIZED");
        var stolenWithoutFreshAssurance = new PolicyGovernanceActor(requester.id(), requester.authorities(),
                issuer, reviewer);
        assertThatThrownBy(() -> emergency.claim(stolenWithoutFreshAssurance, requested.authorizationId()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var revokedRequester = new PolicyGovernanceActor(requester.id(),
                Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_RETIRE,
                        AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION), issuer, reviewer,
                emergencyEvidence());
        assertThatThrownBy(() -> emergency.claim(revokedRequester, requested.authorizationId()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var issued = emergency.claim(requester, requested.authorizationId());
        assertThatThrownBy(() -> emergency.claim(requester, requested.authorizationId()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var originalTransition = mongoTemplate.findById(requested.authorizationId(),
                ModelPromotionEmergencyAuthorizationDocument.class).transition();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(requested.authorizationId())),
                new Update().set("transition", new ModelPromotionTransitionProposal(identity("d"), scope,
                        use(), ModelPromotionLifecycleState.CHAMPION, 4,
                        ModelPromotionLifecycleState.RETIRED, originalTransition.operationId())),
                ModelPromotionEmergencyAuthorizationDocument.class);
        assertThatThrownBy(() -> emergency.retire(requester, pending.proposalId(),
                requested.authorizationId(), issued.token())).isInstanceOf(SecurityException.class);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(requested.authorizationId())),
                new Update().set("transition", originalTransition),
                ModelPromotionEmergencyAuthorizationDocument.class);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(requested.authorizationId())),
                new Update().set("authorizationRationale", "tampered independent attestation"),
                ModelPromotionEmergencyAuthorizationDocument.class);
        assertThatThrownBy(() -> emergency.retire(requester, pending.proposalId(),
                requested.authorizationId(), issued.token())).isInstanceOf(SecurityException.class);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(requested.authorizationId())),
                new Update().set("authorizationRationale", "I independently verified the incident record"),
                ModelPromotionEmergencyAuthorizationDocument.class);
        assertThat(emergency.request(requester, pending.proposalId(), command))
                .extracting(ModelPromotionEmergencyRetirementService.Summary::authorizationId,
                        ModelPromotionEmergencyRetirementService.Summary::status)
                .containsExactly(requested.authorizationId(), "CLAIMED");
        assertThatThrownBy(() -> emergency.request(requester, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request(command.authorizationId(),
                        "conflicting-review", command.approvalId(), command.incidentId(),
                        command.incidentEvidenceReference(), command.reason())))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class);
        var expiring = emergency.request(requester, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("authorization-expiring",
                        "review-expiring", "approval-expiring", command.incidentId(),
                        command.incidentEvidenceReference(), command.reason()));
        var later = new ModelPromotionEmergencyRetirementService(mongoTemplate,
                new TransactionTemplate(new MongoTransactionManager(databaseFactory)), store,
                retirementWorkflow, reviewerAuthorization, new ObjectMapper(),
                Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC), EMERGENCY_ASSURANCE);
        var refreshedAuthorizer = new PolicyGovernanceActor(authorizer.id(), authorizer.authorities(),
                issuer, authorizer.subject(), new PolicyGovernanceActor.AuthenticationEvidence(
                        "urn:fraud:emergency:high", NOW.plusSeconds(301)));
        assertThatThrownBy(() -> later.authorize(refreshedAuthorizer, expiring.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        emergency.inspect(authorizer, expiring.authorizationId()).intentSha256(),
                        command.incidentEvidenceReference(), "I independently verified the incident record")))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class);
        var revokedRequest = emergency.request(requester, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("authorization-revoked",
                        "review-revoked", "approval-revoked", command.incidentId(),
                        command.incidentEvidenceReference(), command.reason()));
        emergency.authorize(authorizer, revokedRequest.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        emergency.inspect(authorizer, revokedRequest.authorizationId()).intentSha256(),
                        command.incidentEvidenceReference(), "I independently verified the incident record"));
        var revokedToken = emergency.claim(requester, revokedRequest.authorizationId());
        emergency.revoke(authorizer, revokedRequest.authorizationId());
        assertThatThrownBy(() -> emergency.retire(requester, pending.proposalId(),
                revokedRequest.authorizationId(), revokedToken.token()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var compromiseOne = emergency.request(requester, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("compromise-one", "compromise-review-one",
                        "compromise-approval-one", "case-compromise-123", "incident-ledger:compromise-123",
                        "The affected account requires immediate emergency grant revocation"));
        var compromiseTwo = emergency.request(requester, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("compromise-two", "compromise-review-two",
                        "compromise-approval-two", "case-compromise-123", "incident-ledger:compromise-123",
                        "The affected account requires immediate emergency grant revocation"));
        emergency.authorize(authorizer, compromiseOne.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        emergency.inspect(authorizer, compromiseOne.authorizationId()).intentSha256(),
                        "incident-ledger:compromise-123", "I verified the affected account and exact intent"));
        var compromiseToken = emergency.claim(requester, compromiseOne.authorizationId());
        var selector = new ModelPromotionEmergencyRetirementService.AffectedSelector(
                requester.id(), "case-compromise-123");
        assertThat(emergency.affected(authorizer, selector).grants()).hasSize(2);
        assertThat(emergency.revokeAffected(authorizer, selector).grants()).hasSize(2);
        assertThat(emergency.affected(authorizer, selector).grants()).isEmpty();
        assertThatThrownBy(() -> emergency.retire(requester, pending.proposalId(),
                compromiseOne.authorizationId(), compromiseToken.token()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> emergency.retire(requester, pending.proposalId(),
                requested.authorizationId(), "invalid-token"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHAMPION);
        mongoTemplate.insert(new ModelPromotionEmergencyAuditDocument("authorization-retire:consumed",
                requested.authorizationId(), pending.proposalId(), "CONSUMED", requester.id(), issuer, reviewer,
                mongoTemplate.findById(requested.authorizationId(),
                        ModelPromotionEmergencyAuthorizationDocument.class).intentSha256(),
                command.incidentId(), NOW));
        assertThatThrownBy(() -> emergency.retire(requester, pending.proposalId(),
                requested.authorizationId(), issued.token())).isInstanceOf(RuntimeException.class);
        assertThat(mongoTemplate.findById(requested.authorizationId(),
                ModelPromotionEmergencyAuthorizationDocument.class).status())
                .isEqualTo(ModelPromotionEmergencyAuthorizationDocument.Status.CLAIMED);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHAMPION);
        mongoTemplate.remove(Query.query(Criteria.where("_id").is("authorization-retire:consumed")),
                ModelPromotionEmergencyAuditDocument.class);
        AtomicBoolean unknownCommitSignalled = new AtomicBoolean();
        MongoModelPromotionStore restartedStore = storeWithCommitSignal(unknownCommitSignalled, true);
        ModelPromotionWorkflowService restartedWorkflow = new ModelPromotionWorkflowService(restartedStore,
                mock(ModelPromotionEvidenceResolver.class), mock(ModelPromotionAssessmentService.class),
                mock(ModelPromotionPolicyResolver.class), reviewerAuthorization, Clock.fixed(NOW, ZoneOffset.UTC));
        ModelPromotionEmergencyRetirementService restartedEmergency = new ModelPromotionEmergencyRetirementService(
                mongoTemplate, new TransactionTemplate(new MongoTransactionManager(databaseFactory)),
                restartedStore, restartedWorkflow, reviewerAuthorization, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), EMERGENCY_ASSURANCE);
        ModelPromotionProposalResult approved = restartedEmergency.retire(requester, pending.proposalId(),
                requested.authorizationId(), issued.token());
        assertThat(unknownCommitSignalled.get()).isTrue();
        assertThat(approved.idempotentReplay()).isTrue();

        assertThat(pending.policySha256()).isNull();
        assertThat(pending.assessmentSha256()).isNull();
        assertThat(approved.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.APPROVED);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.RETIRED);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionChampionClaimDocument.class)).isZero();
        ModelPromotionDecisionDocument decision = store.history(identity, scope, use(), 10).stream()
                .filter(candidate -> candidate.resultingState() == ModelPromotionLifecycleState.RETIRED)
                .findFirst()
                .orElseThrow();
        assertThat(decision.policySnapshot()).isNull();
        assertThat(decision.assessmentSnapshot()).isNull();
        assertThat(decision.proposal().rationale().content()).isEqualTo(RATIONALE);
        assertThat(decision.approval().approvedBy())
                .isEqualTo(PolicyGovernanceActor.identityId(issuer, "retirement-reviewer"));
        assertThat(store.findProposal(pending.proposalId()).orElseThrow().reviewAudit().action())
                .isEqualTo("EMERGENCY_RETIRE_MODEL_PROMOTION_PROPOSAL");
        assertThatThrownBy(() -> emergency.retire(requester, pending.proposalId(),
                requested.authorizationId(), issued.token())).isInstanceOf(RuntimeException.class);
        PolicyGovernanceActor auditor = new PolicyGovernanceActor(
                PolicyGovernanceActor.identityId(issuer, "auditor"),
                Set.of(ModelPolicyAuthority.AUDIT_READ, AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION),
                issuer, "auditor");
        assertThat(emergency.auditView(auditor, requested.authorizationId()).proofStatus())
                .isEqualTo("INDEPENDENT_AUTHORIZATION_AND_RETIREMENT_COMMITTED");
    }

    @Test
    void testScopeCannotRequestProductionEmergencyGrant() {
        ExactModelArtifactIdentity identity = identity("b");
        ModelGovernanceScope scope = scope("TEST");
        advanceToPromotionRecommended(identity, scope, "test-emergency");
        acceptTransition(identity, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "test-emergency-champion");
        var pending = store.submitProposal(retirementProposal(identity, scope,
                "test-emergency-retirement", "test-emergency-proposal", "test-proposer"));
        String issuer = "https://issuer.example";
        String subject = "test-reviewer";
        var requester = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, subject), Set.of(
                AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST,
                AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_RETIRE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST), issuer, subject, emergencyEvidence());
        var authorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        var workflow = workflow(store, mock(ModelPromotionEvidenceResolver.class),
                mock(ModelPromotionAssessmentService.class), mock(ModelPromotionPolicyResolver.class));
        var emergency = new ModelPromotionEmergencyRetirementService(mongoTemplate,
                new TransactionTemplate(new MongoTransactionManager(databaseFactory)), store, workflow,
                authorization, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), EMERGENCY_ASSURANCE);
        authenticate(subject, requester.authorities());
        assertThatThrownBy(() -> emergency.request(requester, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("test-grant", "test-review",
                        "test-approval", "case-12345", "incident-ledger:case-12345",
                        "The exact test model has an independently documented incident")))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class);
    }

    @Test
    void concurrentEmergencyReviewersConsumeOnlyOneGrant() throws Exception {
        ExactModelArtifactIdentity identity = identity("c");
        ModelGovernanceScope scope = scope("PRODUCTION");
        advanceToPromotionRecommended(identity, scope, "emergency-race");
        acceptTransition(identity, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "emergency-race-champion");
        var pending = store.submitProposal(retirementProposal(identity, scope,
                "emergency-race-retirement", "emergency-race-proposal", "race-proposer"));
        String issuer = "https://issuer.example";
        var mandates = Set.of(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST,
                AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_RETIRE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION);
        var reviewerOne = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, "race-one"),
                mandates, issuer, "race-one", emergencyEvidence());
        var reviewerTwo = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, "race-two"),
                mandates, issuer, "race-two", emergencyEvidence());
        var authorizer = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, "race-authorizer"),
                Set.of(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE,
                        AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION), issuer, "race-authorizer",
                emergencyEvidence());
        var reviewerAuthorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        var workflow = new ModelPromotionWorkflowService(store, mock(ModelPromotionEvidenceResolver.class),
                mock(ModelPromotionAssessmentService.class), mock(ModelPromotionPolicyResolver.class),
                reviewerAuthorization, Clock.fixed(NOW, ZoneOffset.UTC));
        var emergency = new ModelPromotionEmergencyRetirementService(mongoTemplate,
                new TransactionTemplate(new MongoTransactionManager(databaseFactory)), store, workflow,
                reviewerAuthorization, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), EMERGENCY_ASSURANCE);
        emergency.ensureIndexes();
        authenticate("race-one", mandates);
        var first = emergency.request(reviewerOne, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("race-grant-one", "race-review-one",
                        "race-approval-one", "case-race-123", "incident-ledger:case-race-123",
                        "Independently confirmed emergency for the exact production champion"));
        authenticate("race-two", mandates);
        var second = emergency.request(reviewerTwo, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("race-grant-two", "race-review-two",
                        "race-approval-two", "case-race-123", "incident-ledger:case-race-123",
                        "Independently confirmed emergency for the exact production champion"));
        emergency.authorize(authorizer, first.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        emergency.inspect(authorizer, first.authorizationId()).intentSha256(),
                        "incident-ledger:case-race-123", "I independently verified the first retirement request"));
        emergency.authorize(authorizer, second.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        emergency.inspect(authorizer, second.authorizationId()).intentSha256(),
                        "incident-ledger:case-race-123", "I independently verified the second retirement request"));
        authenticate("race-one", mandates);
        var firstToken = emergency.claim(reviewerOne, first.authorizationId());
        authenticate("race-two", mandates);
        var secondToken = emergency.claim(reviewerTwo, second.authorizationId());
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var attempts = List.<Callable<Boolean>>of(
                    () -> emergencyRetireAfter(start, emergency, reviewerOne, mandates,
                            pending.proposalId(), firstToken),
                    () -> emergencyRetireAfter(start, emergency, reviewerTwo, mandates,
                            pending.proposalId(), secondToken));
            var futures = attempts.stream().map(executor::submit).toList();
            start.countDown();
            assertThat((futures.get(0).get() ? 1 : 0) + (futures.get(1).get() ? 1 : 0)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.RETIRED);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionChampionClaimDocument.class)).isZero();
        assertThat(mongoTemplate.count(Query.query(Criteria.where("status").is("CONSUMED")),
                ModelPromotionEmergencyAuthorizationDocument.class)).isEqualTo(1);
        assertThat(store.history(identity, scope, use(), 10).stream()
                .filter(decision -> decision.resultingState() == ModelPromotionLifecycleState.RETIRED))
                .hasSize(1);
    }

    @Test
    void emergencyRevocationAndConsumptionCannotBothCommit() throws Exception {
        ExactModelArtifactIdentity identity = identity("e");
        ModelGovernanceScope scope = scope("PRODUCTION");
        advanceToPromotionRecommended(identity, scope, "emergency-revoke-race");
        acceptTransition(identity, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "emergency-revoke-race-champion");
        var pending = store.submitProposal(retirementProposal(identity, scope,
                "emergency-revoke-race-retirement", "emergency-revoke-race-proposal", "race-proposer"));
        String issuer = "https://issuer.example";
        var mandates = Set.of(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST,
                AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_RETIRE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION);
        var requester = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, "race-requester"),
                mandates, issuer, "race-requester", emergencyEvidence());
        var authorizer = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, "race-authorizer"),
                Set.of(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE,
                        AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION), issuer, "race-authorizer",
                emergencyEvidence());
        var reviewerAuthorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        var workflow = new ModelPromotionWorkflowService(store, mock(ModelPromotionEvidenceResolver.class),
                mock(ModelPromotionAssessmentService.class), mock(ModelPromotionPolicyResolver.class),
                reviewerAuthorization, Clock.fixed(NOW, ZoneOffset.UTC));
        var emergency = new ModelPromotionEmergencyRetirementService(mongoTemplate,
                new TransactionTemplate(new MongoTransactionManager(databaseFactory)), store, workflow,
                reviewerAuthorization, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), EMERGENCY_ASSURANCE);
        emergency.ensureIndexes();
        authenticate(requester.subject(), mandates);
        var requested = emergency.request(requester, pending.proposalId(),
                new ModelPromotionEmergencyRetirementService.Request("revoke-race-grant", "revoke-race-review",
                        "revoke-race-approval", "case-revoke-race", "incident-ledger:revoke-race",
                        "Independently documented incident requires exact model retirement"));
        emergency.authorize(authorizer, requested.authorizationId(),
                new ModelPromotionEmergencyRetirementService.AuthorizationAttestation(
                        emergency.inspect(authorizer, requested.authorizationId()).intentSha256(),
                        "incident-ledger:revoke-race", "I verified the precise incident evidence and model"));
        var claimed = emergency.claim(requester, requested.authorizationId());
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var retire = executor.submit(() -> emergencyRetireAfter(start, emergency, requester,
                    mandates, pending.proposalId(), claimed));
            var revoke = executor.submit(() -> {
                start.await();
                try { emergency.revoke(authorizer, requested.authorizationId()); return true; }
                catch (RuntimeException expectedConflict) { return false; }
            });
            start.countDown();
            assertThat((retire.get() ? 1 : 0) + (revoke.get() ? 1 : 0)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        var grant = mongoTemplate.findById(requested.authorizationId(),
                ModelPromotionEmergencyAuthorizationDocument.class);
        assertThat(grant.status()).isIn(ModelPromotionEmergencyAuthorizationDocument.Status.CONSUMED,
                ModelPromotionEmergencyAuthorizationDocument.Status.REVOKED);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(grant.status() == ModelPromotionEmergencyAuthorizationDocument.Status.CONSUMED
                        ? ModelPromotionLifecycleState.RETIRED : ModelPromotionLifecycleState.CHAMPION);
    }

    private boolean emergencyRetireAfter(CountDownLatch start, ModelPromotionEmergencyRetirementService emergency,
            PolicyGovernanceActor actor, Set<String> mandates, String proposalId,
            ModelPromotionEmergencyRetirementService.IssuedGrant grant) throws InterruptedException {
        start.await();
        authenticate(actor.subject(), mandates);
        try {
            emergency.retire(actor, proposalId, grant.authorizationId(), grant.token());
            return true;
        } catch (RuntimeException expectedConflict) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @RepeatedTest(12)
    void concurrentIndependentReviewsProduceOneAcceptedDecision() throws Exception {
        ExactModelArtifactIdentity identity = identity("5");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-concurrent-review"));
        store.submitProposal(proposal(identity, scope, "proposal-concurrent", "proposal-2", "proposer"));

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> reviewAfter(start, review("proposal-2", "review-one", "reviewer-one")),
                    () -> reviewAfter(start, review("proposal-2", "review-two", "reviewer-two"))
            );
            var futures = new ArrayList<>(attempts.stream().map(executor::submit).toList());
            start.countDown();
            long successes = 0;
            for (var future : futures) {
                if (future.get()) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        ModelPromotionAggregate finalState = store.currentState(identity, scope, use()).orElseThrow();
        assertThat(finalState.lifecycleState()).isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(finalState.revision()).isEqualTo(1);
        ModelPromotionProposalDocument finalProposal = store.findProposal("proposal-2").orElseThrow();
        assertThat(finalProposal.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.APPROVED);
        assertThat(finalProposal.reviewAudit()).isNotNull();
        assertThat(finalProposal.reviewOperationId()).isIn("review-one", "review-two");
        assertThat(mongoTemplate.count(new Query(), ModelPromotionProposalDocument.class)).isEqualTo(1);
        List<ModelPromotionDecisionDocument> accepted = store.history(identity, scope, use(), 10).stream()
                .filter(decision -> decision.eventType()
                        == ModelPromotionDecisionDocument.EventType.LIFECYCLE_TRANSITION_ACCEPTED).toList();
        assertThat(accepted).hasSize(1);
        assertThat(accepted.getFirst().localAudit()).isNotNull();
        assertThat(accepted.getFirst().operationId()).isEqualTo(finalProposal.reviewOperationId());
        assertThat(accepted.getFirst().resultingRevision()).isEqualTo(1);
        String losingOperation = "review-one".equals(finalProposal.reviewOperationId()) ? "review-two" : "review-one";
        assertThat(mongoTemplate.findById(ModelPromotionPersistenceKey.operation(
                finalProposal.aggregateKey(), losingOperation), ModelPromotionOperationClaimDocument.class)).isNull();
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(3);
    }

    @Test
    void concurrentApprovalAndRejectionLeaveExactlyOneAuditedDecision() throws Exception {
        ExactModelArtifactIdentity identity = identity("5");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-mixed-review"));
        store.submitProposal(proposal(identity, scope, "submit-mixed-review", "proposal-mixed-review", "proposer"));

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var approval = executor.submit(() -> reviewAfter(start, review(
                    "proposal-mixed-review", "approve-mixed", "reviewer-approve",
                    ModelPromotionApproval.Outcome.APPROVED)));
            var rejection = executor.submit(() -> reviewAfter(start, review(
                    "proposal-mixed-review", "reject-mixed", "reviewer-reject",
                    ModelPromotionApproval.Outcome.REJECTED)));
            start.countDown();
            assertThat(List.of(approval.get(), rejection.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        ModelPromotionProposalDocument durable = store.findProposal("proposal-mixed-review").orElseThrow();
        assertThat(durable.reviewStatus()).isIn(
                ModelPromotionProposalDocument.ReviewStatus.APPROVED,
                ModelPromotionProposalDocument.ReviewStatus.REJECTED);
        assertThat(durable.reviewAudit()).isNotNull();
        List<ModelPromotionDecisionDocument> decisions = store.history(identity, scope, use(), 10);
        assertThat(decisions).hasSize(2);
        assertThat(decisions.stream().filter(decision -> decision.operationId().equals(durable.reviewOperationId())))
                .singleElement().satisfies(decision -> assertThat(decision.localAudit()).isNotNull());
        ModelPromotionAggregate state = store.currentState(identity, scope, use()).orElseThrow();
        assertThat(state.lifecycleState()).isEqualTo(durable.reviewStatus()
                == ModelPromotionProposalDocument.ReviewStatus.APPROVED
                ? ModelPromotionLifecycleState.CHALLENGER : ModelPromotionLifecycleState.CANDIDATE);
        assertThat(state.revision()).isEqualTo(durable.reviewStatus()
                == ModelPromotionProposalDocument.ReviewStatus.APPROVED ? 1 : 0);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(3);
    }

    @Test
    void identicalConcurrentReviewRetriesReplayOneDecision() throws Exception {
        ExactModelArtifactIdentity identity = identity("5");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-identical"));
        store.submitProposal(proposal(identity, scope, "submit-identical", "proposal-identical", "proposer"));
        ModelPromotionProposalReview command = review("proposal-identical", "review-identical", "same-reviewer");

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ModelPromotionProposalResult> attempt = () -> {
                start.await();
                return store.reviewProposal(command, () -> {
                });
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get().idempotentReplay(), second.get().idempotentReplay()))
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(3);
    }

    @Test
    void differentReviewersCannotShareOneOperationId() throws Exception {
        ExactModelArtifactIdentity identity = identity("5");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-shared-review"));
        store.submitProposal(proposal(identity, scope, "submit-shared-review", "proposal-shared-review", "proposer"));

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> reviewAfter(start, review(
                    "proposal-shared-review", "review-shared", "reviewer-one")));
            var second = executor.submit(() -> reviewAfter(start, review(
                    "proposal-shared-review", "review-shared", "reviewer-two")));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(3);
    }

    @Test
    void eightConcurrentReviewsSettleWithOneDurableDecision() throws Exception {
        ExactModelArtifactIdentity identity = identity("5");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-eight-reviewers"));
        store.submitProposal(proposal(identity, scope, "submit-eight-reviewers", "proposal-eight-reviewers", "proposer"));

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                int reviewer = index;
                attempts.add(() -> reviewAfter(start, review("proposal-eight-reviewers",
                        "review-eight-" + reviewer, "reviewer-eight-" + reviewer)));
            }
            var futures = attempts.stream().map(executor::submit).toList();
            start.countDown();
            long successes = 0;
            for (var future : futures) if (future.get()) successes++;
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.findProposal("proposal-eight-reviewers").orElseThrow().reviewAudit()).isNotNull();
        assertThat(store.currentState(identity, scope, use()).orElseThrow().revision()).isEqualTo(1);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(3);
    }

    @Test
    void unknownCommitResultReadsDurableDecisionWithoutSecondMutation() {
        ExactModelArtifactIdentity identity = identity("5");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-unknown-commit"));
        store.submitProposal(proposal(identity, scope, "submit-unknown-commit", "proposal-unknown-commit", "proposer"));
        AtomicBoolean signal = new AtomicBoolean();
        MongoModelPromotionStore uncertain = storeWithCommitSignal(signal, true);
        ModelPromotionProposalReview command = review(
                "proposal-unknown-commit", "review-unknown-commit", "independent-reviewer");

        ModelPromotionProposalResult recovered = uncertain.reviewProposal(command, () -> {
        });

        assertThat(signal.get()).isTrue();
        assertThat(recovered.idempotentReplay()).isTrue();
        assertThat(store.currentState(identity, scope, use()).orElseThrow().revision()).isEqualTo(1);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
        assertThat(store.findProposal(command.proposalId()).orElseThrow().reviewAudit()).isNotNull();
        assertThat(store(new ModelPromotionLocalAuditFactory(), validator).reviewProposal(command, () -> {
            throw new AssertionError("Restart replay must not execute mutable checks");
        }).idempotentReplay()).isTrue();
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
    }

    @Test
    void unknownCommitResultWithoutDurableDecisionRemainsUnavailable() {
        ExactModelArtifactIdentity identity = identity("5");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-no-commit"));
        store.submitProposal(proposal(identity, scope, "submit-no-commit", "proposal-no-commit", "proposer"));
        AtomicBoolean signal = new AtomicBoolean();
        MongoModelPromotionStore uncertain = storeWithCommitSignal(signal, false);

        assertThatThrownBy(() -> uncertain.reviewProposal(
                review("proposal-no-commit", "review-no-commit", "independent-reviewer"), () -> {
                })).isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("outcome is unknown");
        assertThat(signal.get()).isTrue();
        assertThat(store.findProposal("proposal-no-commit").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().revision()).isZero();
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(2);
    }

    @Test
    void temporaryTransientMongoFailureRetriesOneCompleteReviewTransaction() {
        ExactModelArtifactIdentity identity = identity("5");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-temporary-failure"));
        store.submitProposal(proposal(identity, scope, "submit-temporary-failure",
                "proposal-temporary-failure", "proposer"));
        AtomicBoolean injected = new AtomicBoolean();
        MongoModelPromotionStore recovering = storeWithOneTransientFailure(injected);

        ModelPromotionProposalResult result = recovering.reviewProposal(review(
                "proposal-temporary-failure", "review-temporary-failure", "independent-reviewer"), () -> {
        });

        assertThat(injected.get()).isTrue();
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(store.currentState(identity, scope, use()).orElseThrow().revision()).isEqualTo(1);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
        assertThat(mongoTemplate.count(new Query(), ModelPromotionOperationClaimDocument.class)).isEqualTo(3);
    }

    @Test
    void rejectedProposalsAfterAnAcceptedTransitionPreserveAcceptedIdentityAndReplaySafely() {
        ExactModelArtifactIdentity identity = ModelPromotionAssessmentTestFixtures.identity();
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-rejection"));
        store.submitProposal(proposal(identity, scope, "proposal-challenger", "proposal-challenger", "proposer"));
        store.reviewProposal(review("proposal-challenger", "approve-challenger", "reviewer"), () -> {
        });
        ModelPromotionAggregate accepted = store.currentState(identity, scope, use()).orElseThrow();
        String acceptedDecisionId = accepted.acceptedDecisionId();
        store.submitProposal(shadowProposal(proposal(
                identity,
                scope,
                ModelPromotionLifecycleState.CHALLENGER,
                1,
                ModelPromotionLifecycleState.SHADOW_APPROVED,
                "proposal-shadow-one",
                "proposal-shadow-one",
                "proposer"
        )));
        ModelPromotionProposalReview rejection = review(
                "proposal-shadow-one", "reject-shadow-one", "reviewer", ModelPromotionApproval.Outcome.REJECTED
        );

        ModelPromotionProposalResult rejected = store.reviewProposal(rejection, () -> {
        });
        ModelPromotionProposalResult replay = store.reviewProposal(rejection, () -> {
        });

        assertThat(rejected.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.REJECTED);
        assertThat(replay.idempotentReplay()).isTrue();
        ModelPromotionAggregate afterFirstRejection = store.currentState(identity, scope, use()).orElseThrow();
        assertThat(afterFirstRejection.lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(afterFirstRejection.revision()).isEqualTo(1);
        assertThat(afterFirstRejection.acceptedDecisionId()).isEqualTo(acceptedDecisionId);
        assertThat(store.findProposal("proposal-shadow-one").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.REJECTED);
        assertThat(rejectionDecisions(identity, scope))
                .hasSize(1)
                .allSatisfy(decision -> assertThat(decision.decisionId()).isNull());
        assertThatThrownBy(() -> store.reviewProposal(
                review(
                        "proposal-shadow-one",
                        "reject-shadow-one",
                        "reviewer",
                        ModelPromotionApproval.Outcome.REJECTED,
                        "A materially different rejection rationale"
                ),
                () -> {
                }
        )).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("different canonical content");

        store.submitProposal(shadowProposal(proposal(
                identity,
                scope,
                ModelPromotionLifecycleState.CHALLENGER,
                1,
                ModelPromotionLifecycleState.SHADOW_APPROVED,
                "proposal-shadow-two",
                "proposal-shadow-two",
                "proposer"
        )));
        store.reviewProposal(review(
                "proposal-shadow-two", "reject-shadow-two", "reviewer", ModelPromotionApproval.Outcome.REJECTED
        ), () -> {
        });

        ModelPromotionAggregate afterSecondRejection = store.currentState(identity, scope, use()).orElseThrow();
        assertThat(afterSecondRejection.lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(afterSecondRejection.revision()).isEqualTo(1);
        assertThat(afterSecondRejection.acceptedDecisionId()).isEqualTo(acceptedDecisionId);
        assertThat(store.findProposal("proposal-shadow-two").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.REJECTED);
        assertThat(rejectionDecisions(identity, scope))
                .hasSize(2)
                .allSatisfy(decision -> {
                    assertThat(decision.decisionId()).isNull();
                    assertThat(decision.resultingState()).isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
                    assertThat(decision.resultingRevision()).isEqualTo(1);
                });
    }

    @Test
    void reviewAuditFailureRollsBackApprovalRationaleAndLifecycleTransition() {
        ExactModelArtifactIdentity identity = identity("b");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-review-audit-failure"));
        store.submitProposal(proposal(
                identity, scope, "proposal-review-audit-failure", "proposal-review-audit-failure", "proposer"
        ));
        MongoModelPromotionStore failing = store(new ModelPromotionLocalAuditFactory() {
            @Override
            public ModelPromotionDecisionDocument.LocalAuditEvidence accepted(
                    String action, ModelPromotionOperationContext context, Instant committedAt
            ) {
                if (action.equals("APPROVE_MODEL_PROMOTION_PROPOSAL")) {
                    throw new IllegalStateException("local review audit unavailable");
                }
                return super.accepted(action, context, committedAt);
            }
        }, validator);

        assertThatThrownBy(() -> failing.reviewProposal(
                review("proposal-review-audit-failure", "review-audit-failure", "independent-reviewer"), () -> {
                }, () -> mongoTemplate.insert(new org.bson.Document("_id", "signed-proof-review-audit-failure"),
                        "model_promotion_signed_review_rollback_probe")
        )).hasMessageContaining("local review audit unavailable");

        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        ModelPromotionProposalDocument pending = store.findProposal("proposal-review-audit-failure").orElseThrow();
        assertThat(pending.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(pending.approval()).isNull();
        assertThat(mongoTemplate.getCollection("model_promotion_signed_review_rollback_probe")
                .countDocuments()).isZero();
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
        assertThat(mongoTemplate.findById(
                ModelPromotionPersistenceKey.operation(
                        ModelPromotionPersistenceKey.aggregate(identity, scope, use()), "review-audit-failure"
                ), ModelPromotionOperationClaimDocument.class
        )).isNull();
    }

    @Test
    void rejectionAuditFailureRollsBackReviewStatusAndRejectionDecision() {
        ExactModelArtifactIdentity identity = identity("7");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-rejection-audit"));
        store.submitProposal(proposal(identity, scope, "proposal-rejection-audit", "proposal-rejection-audit", "proposer"));
        MongoModelPromotionStore failing = store(new ModelPromotionLocalAuditFactory() {
            @Override
            public ModelPromotionDecisionDocument.LocalAuditEvidence accepted(
                    String action, ModelPromotionOperationContext context, Instant committedAt
            ) {
                if (action.equals("REJECT_MODEL_PROMOTION_PROPOSAL")) {
                    throw new IllegalStateException("rejection audit unavailable");
                }
                return super.accepted(action, context, committedAt);
            }
        }, validator);

        assertThatThrownBy(() -> failing.reviewProposal(
                review(
                        "proposal-rejection-audit",
                        "reject-rejection-audit",
                        "independent-reviewer",
                        ModelPromotionApproval.Outcome.REJECTED
                ),
                () -> {
                }
        )).hasMessageContaining("rejection audit unavailable");

        assertThat(store.findProposal("proposal-rejection-audit").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(rejectionDecisions(identity, scope)).isEmpty();
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
        assertThat(mongoTemplate.findById(
                ModelPromotionPersistenceKey.operation(
                        ModelPromotionPersistenceKey.aggregate(identity, scope, use()), "reject-rejection-audit"
                ), ModelPromotionOperationClaimDocument.class
        )).isNull();
    }

    @Test
    void existingRejectionDecisionOperationConflictDoesNotAdvanceReviewStatus() {
        ExactModelArtifactIdentity identity = identity("8");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-rejection-insert"));
        store.submitProposal(proposal(identity, scope, "proposal-rejection-insert", "proposal-rejection-insert", "proposer"));
        ModelPromotionProposalReview review = review(
                "proposal-rejection-insert",
                "reject-rejection-insert",
                "independent-reviewer",
                ModelPromotionApproval.Outcome.REJECTED
        );
        String aggregateKey = ModelPromotionPersistenceKey.aggregate(identity, scope, use());
        mongoTemplate.insert(rejectionDecision(aggregateKey, identity, scope, review));
        long historyBeforeReview = store.history(identity, scope, use(), 10).size();

        assertThatThrownBy(() -> store.reviewProposal(review, () -> {
        })).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("operation ID");

        assertThat(store.findProposal("proposal-rejection-insert").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(store.history(identity, scope, use(), 10)).hasSize((int) historyBeforeReview);
    }

    @Test
    void startupGuardFailsClosedWhenRequiredIndexIsMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RegulatedMutationTransactionCapabilityProbe> probeProvider = mock(ObjectProvider.class);
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        when(probeProvider.getIfAvailable()).thenReturn(probe);
        ModelPromotionPersistenceStartupGuard guard = new ModelPromotionPersistenceStartupGuard(
                mongoTemplate,
                requiredTransactionRunner(),
                probeProvider,
                true
        );

        org.assertj.core.api.Assertions.assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class)
                .dropIndex(ModelPromotionDecisionDocument.OPERATION_INDEX);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INDEX_MISSING:" + ModelPromotionDecisionDocument.OPERATION_INDEX);
    }

    @Test
    void disabledStartupVerificationDoesNotDisableIndexBootstrap() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RegulatedMutationTransactionCapabilityProbe> probeProvider = mock(ObjectProvider.class);
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        when(probeProvider.getIfAvailable()).thenReturn(probe);
        ModelPromotionPersistenceStartupGuard disabledGuard = new ModelPromotionPersistenceStartupGuard(
                mongoTemplate, requiredTransactionRunner(), probeProvider, false
        );
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class)
                .dropIndex(ModelPromotionDecisionDocument.OPERATION_INDEX);

        org.assertj.core.api.Assertions.assertThatCode(() -> disabledGuard.run(null)).doesNotThrowAnyException();
        verifyNoInteractions(probe);

        new ModelPromotionPersistenceIndexInitializer(mongoTemplate).ensureIndexes();
        assertThat(mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).getIndexInfo())
                .anySatisfy(index -> assertThat(index.getName())
                        .isEqualTo(ModelPromotionDecisionDocument.OPERATION_INDEX));
    }

    @Test
    void indexBootstrapRejectsConflictingNonUniqueDecisionIndexEvenWithoutStartupVerification() {
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class)
                .dropIndex(ModelPromotionDecisionDocument.OPERATION_INDEX);
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).ensureIndex(
                new org.springframework.data.mongodb.core.index.Index()
                        .on("aggregate_key", org.springframework.data.domain.Sort.Direction.ASC)
                        .on("operation_id", org.springframework.data.domain.Sort.Direction.ASC)
                        .named(ModelPromotionDecisionDocument.OPERATION_INDEX)
        );

        assertThatThrownBy(() -> new ModelPromotionPersistenceIndexInitializer(mongoTemplate).ensureIndexes())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void startupGuardRejectsDecisionIndexWithoutSparseSemantics() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RegulatedMutationTransactionCapabilityProbe> probeProvider = mock(ObjectProvider.class);
        when(probeProvider.getIfAvailable()).thenReturn(mock(RegulatedMutationTransactionCapabilityProbe.class));
        ModelPromotionPersistenceStartupGuard guard = new ModelPromotionPersistenceStartupGuard(
                mongoTemplate, requiredTransactionRunner(), probeProvider, true
        );
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class)
                .dropIndex(ModelPromotionDecisionDocument.DECISION_INDEX);
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).ensureIndex(
                new org.springframework.data.mongodb.core.index.Index()
                        .on("decision_id", org.springframework.data.domain.Sort.Direction.ASC)
                        .unique()
                        .named(ModelPromotionDecisionDocument.DECISION_INDEX)
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INDEX_SPARSE_INVALID:" + ModelPromotionDecisionDocument.DECISION_INDEX);
    }

    private boolean reviewAfter(CountDownLatch start, ModelPromotionProposalReview review) throws Exception {
        start.await();
        try {
            store.reviewProposal(review, () -> {
            });
            return true;
        } catch (ModelPromotionPersistenceConflictException expected) {
            return false;
        }
    }

    private boolean submitAfter(CountDownLatch start, ModelPromotionProposalSubmission proposal) throws Exception {
        start.await();
        try {
            store.submitProposal(proposal);
            return true;
        } catch (ModelPromotionPersistenceConflictException expected) {
            return false;
        }
    }

    private void advanceToPromotionRecommended(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            String suffix
    ) {
        store.registerCandidate(registration(identity, scope, "register-" + suffix));
        acceptTransition(identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHALLENGER, "challenger-" + suffix);
        acceptTransition(identity, scope, ModelPromotionLifecycleState.CHALLENGER, 1,
                ModelPromotionLifecycleState.SHADOW_APPROVED, "shadow-" + suffix);
        acceptTransition(identity, scope, ModelPromotionLifecycleState.SHADOW_APPROVED, 2,
                ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, "recommend-" + suffix);
    }

    private ModelPromotionProposalResult acceptTransition(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            ModelPromotionLifecycleState source,
            long revision,
            ModelPromotionLifecycleState target,
            String operationId
    ) {
        String proposalId = "proposal-" + operationId;
        store.submitProposal(proposal(identity, scope, source, revision, target,
                operationId, proposalId, "proposer-" + operationId));
        return store.reviewProposal(review(proposalId, "review-" + operationId, "reviewer-" + operationId), () -> {
        });
    }

    private MongoModelPromotionStore store(
            ModelPromotionLocalAuditFactory auditFactory,
            ModelPromotionDecisionValidator decisionValidator
    ) {
        RegulatedMutationTransactionRunner runner = requiredTransactionRunner();
        return new MongoModelPromotionStore(
                mongoTemplate, runner, decisionValidator, auditFactory,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private RegulatedMutationTransactionRunner requiredTransactionRunner() {
        MongoTransactionManager manager = new MongoTransactionManager(databaseFactory);
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> managerProvider = mock(ObjectProvider.class);
        when(managerProvider.getIfAvailable()).thenReturn(manager);
        return new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED.name(),
                managerProvider
        );
    }

    private MongoModelPromotionStore storeWithCommitSignal(AtomicBoolean signal, boolean commitBeforeSignal) {
        MongoTransactionManager manager = new MongoTransactionManager(databaseFactory);
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> managerProvider = mock(ObjectProvider.class);
        when(managerProvider.getIfAvailable()).thenReturn(manager);
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED.name(), managerProvider) {
            @Override
            public <T> T runLocalCommit(Supplier<T> callback) {
                if (!signal.compareAndSet(false, true)) {
                    throw new AssertionError("An unknown commit result must not rerun the transaction");
                }
                if (commitBeforeSignal) super.runLocalCommit(callback);
                MongoException unknown = new MongoException(91, "injected unknown commit result");
                unknown.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
                throw unknown;
            }
        };
        return new MongoModelPromotionStore(
                mongoTemplate, runner, validator, new ModelPromotionLocalAuditFactory(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private MongoModelPromotionStore storeWithOneTransientFailure(AtomicBoolean injected) {
        MongoTransactionManager manager = new MongoTransactionManager(databaseFactory);
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> managerProvider = mock(ObjectProvider.class);
        when(managerProvider.getIfAvailable()).thenReturn(manager);
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED.name(), managerProvider) {
            @Override
            public <T> T runLocalCommit(Supplier<T> callback) {
                if (injected.compareAndSet(false, true)) {
                    MongoException transientFailure = new MongoException(112, "injected temporary write conflict");
                    transientFailure.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                    throw transientFailure;
                }
                return super.runLocalCommit(callback);
            }
        };
        return new MongoModelPromotionStore(
                mongoTemplate, runner, validator, new ModelPromotionLocalAuditFactory(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private void createRequiredIndexes() {
        new ModelPromotionPersistenceIndexInitializer(mongoTemplate).ensureIndexes();
    }

    private ModelPromotionRegistrationCommand registration(
            ExactModelArtifactIdentity identity, ModelGovernanceScope scope, String operationId
    ) {
        return new ModelPromotionRegistrationCommand(identity, scope, use(), context(operationId));
    }

    private ModelPromotionProposalSubmission proposal(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            String operationId,
            String proposalId,
            String proposer
    ) {
        return proposal(
                identity,
                scope,
                ModelPromotionLifecycleState.CANDIDATE,
                0,
                ModelPromotionLifecycleState.CHALLENGER,
                operationId,
                proposalId,
                proposer
        );
    }

    private ModelPromotionProposalSubmission shadowProposal(ModelPromotionProposalSubmission proposal) {
        ModelPromotionAssessment assessment = ModelPromotionAssessmentTestFixtures.service(
                (scope, use, assessmentTime) -> java.util.Optional.of(ModelPromotionAssessmentTestFixtures.policy())
        ).assess(ModelPromotionAssessmentTestFixtures.completeEvidence(), NOW);
        return new ModelPromotionProposalSubmission(
                proposal.transition(), proposal.operationContext(), proposal.policySnapshot(), assessment,
                proposal.proposal()
        );
    }

    private ModelPromotionProposalSubmission proposal(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            ModelPromotionLifecycleState sourceState,
            long expectedRevision,
            ModelPromotionLifecycleState requestedTargetState,
            String operationId,
            String proposalId,
            String proposer
    ) {
        ModelPromotionTransitionProposal transition = new ModelPromotionTransitionProposal(
                identity,
                scope,
                use(),
                sourceState,
                expectedRevision,
                requestedTargetState,
                operationId
        );
        ModelPromotionOperationContext context = context(operationId, proposer);
        return new ModelPromotionProposalSubmission(
                transition,
                context,
                ModelPromotionAssessmentTestFixtures.policy(),
                targetRequiresAssessment(requestedTargetState) ? persistenceAssessment() : null,
                new ModelPromotionProposal(
                        proposalId,
                        requestedTargetState,
                        context.actorId(),
                        NOW.minusSeconds(30),
                        proposalRationale(proposalId)
                )
        );
    }

    private boolean targetRequiresAssessment(ModelPromotionLifecycleState target) {
        return target == ModelPromotionLifecycleState.SHADOW_APPROVED
                || target == ModelPromotionLifecycleState.PROMOTION_RECOMMENDED
                || target == ModelPromotionLifecycleState.CHAMPION;
    }

    private ModelPromotionAssessment persistenceAssessment() {
        return ModelPromotionAssessmentTestFixtures.service(
                (scope, use, at) -> Optional.of(ModelPromotionAssessmentTestFixtures.policy())
        ).assess(ModelPromotionAssessmentTestFixtures.completeEvidence(), NOW);
    }

    private ModelPromotionProposalReview review(String proposalId, String operationId, String reviewer) {
        return review(proposalId, operationId, reviewer, ModelPromotionApproval.Outcome.APPROVED);
    }

    private ModelPromotionProposalSubmission retirementProposal(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            String operationId,
            String proposalId,
            String proposer
    ) {
        ModelPromotionTransitionProposal transition = new ModelPromotionTransitionProposal(
                identity,
                scope,
                use(),
                ModelPromotionLifecycleState.CHAMPION,
                4,
                ModelPromotionLifecycleState.RETIRED,
                operationId
        );
        ModelPromotionOperationContext context = context(operationId, proposer);
        return new ModelPromotionProposalSubmission(
                transition,
                context,
                null,
                null,
                new ModelPromotionProposal(
                        proposalId,
                        ModelPromotionLifecycleState.RETIRED,
                        context(operationId, proposer).actorId(),
                        NOW.minusSeconds(30),
                        proposalRationale(proposalId)
                )
        );
    }

    private ModelPromotionProposalReview review(
            String proposalId,
            String operationId,
            String reviewer,
            ModelPromotionApproval.Outcome outcome
    ) {
        return review(proposalId, operationId, reviewer, outcome, RATIONALE);
    }

    private ModelPromotionProposalReview review(
            String proposalId,
            String operationId,
            String reviewer,
            ModelPromotionApproval.Outcome outcome,
            String rationale
    ) {
        ModelPromotionOperationContext context = context(operationId, reviewer);
        String approvalId = "approval-" + operationId;
        return new ModelPromotionProposalReview(
                proposalId,
                new ModelPromotionApproval(
                        approvalId,
                        proposalId,
                        outcome,
                        context.actorId(),
                        NOW.minusSeconds(10),
                        approvalRationale(proposalId, approvalId, rationale)
                ),
                context
        );
    }

    private ModelPromotionOperationContext context(String operationId) {
        return context(operationId, "governance-actor");
    }

    private ModelPromotionDecisionRationale proposalRationale(String proposalId) {
        return new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.proposalReference(proposalId), RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE)
        );
    }

    private ModelPromotionDecisionRationale approvalRationale(String proposalId, String approvalId) {
        return approvalRationale(proposalId, approvalId, RATIONALE);
    }

    private ModelPromotionDecisionRationale approvalRationale(String proposalId, String approvalId, String rationale) {
        return new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.approvalReference(proposalId, approvalId), rationale,
                ModelPromotionDecisionRationale.sha256(rationale)
        );
    }

    private List<ModelPromotionDecisionDocument> rejectionDecisions(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope
    ) {
        return store.history(identity, scope, use(), 10).stream()
                .filter(decision -> decision.eventType()
                        == ModelPromotionDecisionDocument.EventType.TRANSITION_PROPOSAL_REJECTED)
                .toList();
    }

    private ModelPromotionDecisionDocument rejectionDecision(
            String aggregateKey,
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            ModelPromotionProposalReview review
    ) {
        return new ModelPromotionDecisionDocument(
                ModelPromotionPersistenceKey.operation(aggregateKey, review.operationContext().operationId()),
                aggregateKey,
                review.operationContext().operationId(),
                "a".repeat(64),
                review.operationContext().actorId(),
                review.operationContext().authorizationFingerprint(),
                ModelPromotionDecisionDocument.EventType.TRANSITION_PROPOSAL_REJECTED,
                identity,
                scope,
                use(),
                ModelPromotionLifecycleState.CANDIDATE,
                ModelPromotionLifecycleState.CANDIDATE,
                0,
                null,
                null,
                null,
                null,
                review.approval(),
                new ModelPromotionDecisionDocument.LocalAuditEvidence(
                        "seeded-rejection-decision",
                        "REJECT_MODEL_PROMOTION_PROPOSAL",
                        "SUCCEEDED",
                        review.operationContext().actorId(),
                        review.operationContext().authorizationFingerprint(),
                        NOW
                ),
                NOW,
                review.operationContext().actorIdentity()
        );
    }

    private ModelPromotionOperationContext context(String operationId, String actorId) {
        ModelPromotionActorIdentity identity = ModelPromotionActorIdentity.institutional(
                "https://issuer.example", actorId);
        return new ModelPromotionOperationContext(operationId, identity.actorId(), AUTHORIZATION, NOW, identity);
    }

    private ModelPromotionWorkflowService workflow(
            MongoModelPromotionStore persistence,
            ModelPromotionEvidenceResolver evidenceResolver,
            ModelPromotionAssessmentService assessmentService,
            ModelPromotionPolicyResolver policyResolver
    ) {
        return new ModelPromotionWorkflowService(
                persistence, evidenceResolver, assessmentService, policyResolver,
                new ModelPromotionAuthorization(new CurrentAnalystUser()), Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private void authenticate(String actorId, Set<String> authorities) {
        String issuer = "https://issuer.example";
        String canonicalActorId = PolicyGovernanceActor.identityId(issuer, actorId);
        AnalystPrincipal principal = new AnalystPrincipal("display-" + actorId, Set.of(), authorities,
                issuer + "#" + actorId, canonicalActorId);
        SecurityContextHolder.getContext().setAuthentication(
                new AnalystAuthenticationFactory().authenticated(principal)
        );
    }

    private ExactModelArtifactIdentity identity(String prefix) {
        return new ExactModelArtifactIdentity(
                "fraud-model", "2026.10-" + prefix, "feature-contract-v2", prefix.repeat(64)
        );
    }

    private ModelGovernanceScope scope(String environment) {
        return new ModelGovernanceScope(
                ModelGovernanceScope.CURRENT_INSTITUTION,
                ModelGovernanceEnvironment.valueOf(environment)
        );
    }

    private ModelPromotionIntendedUse use() {
        return ModelPromotionIntendedUse.FRAUD_RISK_SCORING;
    }

    private static final class PersistenceFocusedValidator extends ModelPromotionDecisionValidator {
        private PersistenceFocusedValidator(ObjectMapper objectMapper) {
            super(
                    objectMapper,
                    new ModelPromotionPolicyValidator(objectMapper),
                    new ModelPromotionPolicyFullTrustVerifier(
                            policy -> ModelPromotionPolicyAuthorityVerification.verified(),
                            policy -> ModelPromotionPolicyApprovalEvidenceVerification.verified())
            );
        }

        @Override
        void validate(DurableModelPromotionTransitionCommand request) {
            // These integration proofs isolate Mongo transaction, CAS, idempotency and uniqueness behavior.
        }

        @Override
        void validateProposal(ModelPromotionProposalSubmission submission) {
            // The policy and assessment contract is covered separately; this suite proves Mongo invariants.
        }
    }
}
