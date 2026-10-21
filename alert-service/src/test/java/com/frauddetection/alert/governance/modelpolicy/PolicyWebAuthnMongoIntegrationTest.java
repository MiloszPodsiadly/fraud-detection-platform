package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceEnvironment;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceScope;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionCriterion;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionIntendedUse;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicy;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyValidator;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.upokecenter.cbor.CBORObject;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class PolicyWebAuthnMongoIntegrationTest extends AbstractIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");
    private static final String ORIGIN = "http://localhost:4173";
    private SimpleMongoClientDatabaseFactory factory;
    private MongoTemplate mongo;
    private MutableClock clock;
    private PolicyGovernanceService governance;
    private PolicyWebAuthnService webAuthn;
    private PolicyCredentialEnrollmentGrantService grants;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        factory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb()
                .getReplicaSetUrl("policy_webauthn_" + UUID.randomUUID().toString().replace("-", "")));
        mongo = new MongoTemplate(factory);
        clock = new MutableClock(START);
        MongoTransactionManager manager = new MongoTransactionManager(factory);
        MongoPolicyGovernanceStore governanceStore = new MongoPolicyGovernanceStore(mongo,
                new TransactionTemplate(manager), clock);
        governanceStore.ensureIndexes();
        ObjectMapper mapper = new ObjectMapper();
        governance = new PolicyGovernanceService(governanceStore, new ModelPromotionPolicyValidator(mapper),
                mapper, "publisher-c", "publisher-key", "issuer-d", "issuer-key",
                org.mockito.Mockito.mock(com.frauddetection.alert.governance.modelpromotion.OnlinePolicyApproverDirectory.class));
        PolicyWebAuthnCredentialRepository credentials = new PolicyWebAuthnCredentialRepository(mongo);
        credentials.ensureIndexes();
        grants = new PolicyCredentialEnrollmentGrantService(mongo, manager, governanceStore);
        grants.ensureIndexes();
        Environment environment = new MockEnvironment();
        webAuthn = new PolicyWebAuthnService(mongo, manager, credentials, governance, governanceStore,
                grants, mapper, environment, "localhost", ORIGIN);
        webAuthn.ensureIndexes();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongo != null) mongo.getDb().drop();
        if (factory != null) factory.destroy();
    }

    @Test
    void firstEnrollmentRejectsOidcOnlySelfApprovalWrongIdentityAndStaleGrant() {
        assertThatThrownBy(() -> webAuthn.startRegistration(author(),
                new PolicyWebAuthnService.RegistrationIntent(null)))
                .isInstanceOf(AccessDeniedException.class);
        String id = "enrollment-" + UUID.randomUUID();
        grants.request(credentialAdmin("security-operator-a"),
                new PolicyCredentialEnrollmentGrantService.GrantRequest(id, author().issuer(),
                        author().subject(), PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST, "case-1"));
        assertThatThrownBy(() -> grants.approve(credentialAdmin("security-operator-a"), id))
                .isInstanceOf(AccessDeniedException.class);
        String token = grants.approve(credentialAdmin("security-operator-b"), id).token();
        assertThatThrownBy(() -> webAuthn.startRegistration(approver(),
                new PolicyWebAuthnService.RegistrationIntent(token)))
                .isInstanceOf(AccessDeniedException.class);
        var wrongIssuer = actor("author", Set.of(ModelPolicyAuthority.CREDENTIAL_ENROLL));
        wrongIssuer = new PolicyGovernanceActor(wrongIssuer.id(), wrongIssuer.authorities(),
                "https://untrusted.example", wrongIssuer.subject());
        PolicyGovernanceActor alien = wrongIssuer;
        assertThatThrownBy(() -> webAuthn.startRegistration(alien,
                new PolicyWebAuthnService.RegistrationIntent(token)))
                .isInstanceOf(AccessDeniedException.class);
        clock.set(clock.instant().plusSeconds(601));
        assertThatThrownBy(() -> webAuthn.startRegistration(author(),
                new PolicyWebAuthnService.RegistrationIntent(token)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void registrationGrantIsSingleUseAndAuditFailureRollsBack() throws Exception {
        Authenticator key = new Authenticator(author());
        key.credentialId = key.newCredentialId();
        String token = grant(author(), PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST);
        var options = webAuthn.startRegistration(author(), new PolicyWebAuthnService.RegistrationIntent(token));
        var request = PublicKeyCredentialCreationOptions.fromJson(mongo.findById(options.challengeId(),
                PolicyWebAuthnChallenge.class).requestJson());
        var command = new PolicyWebAuthnService.FinishCeremony(options.challengeId(),
                key.registration(request.getChallenge().getBase64Url()), token);
        String grantId = token.substring(0, token.lastIndexOf('.'));
        mongo.insert(new PolicyCredentialSecurityEventDocument("credential-enrollment:" + grantId,
                "collision", author().id(), author().issuer(), author().subject(), author().id(), null,
                "case", key.credentialId, clock.instant()));
        assertThatThrownBy(() -> webAuthn.finishRegistration(author(), command))
                .isInstanceOf(RuntimeException.class);
        assertThat(mongo.findById(options.challengeId(), PolicyWebAuthnChallenge.class).used()).isFalse();
        assertThat(mongo.findById(grantId, PolicyCredentialEnrollmentGrantDocument.class).consumedAt()).isNull();
        assertThat(mongo.findById(key.credentialId, PolicyWebAuthnCredential.class)).isNull();
        mongo.remove(Query.query(Criteria.where("_id").is("credential-enrollment:" + grantId)),
                PolicyCredentialSecurityEventDocument.class);
        webAuthn.finishRegistration(author(), command);
        assertThatThrownBy(() -> webAuthn.finishRegistration(author(), command))
                .isInstanceOf(RuntimeException.class);
        assertThat(mongo.count(Query.query(Criteria.where("_id").is("credential-enrollment:" + grantId)),
                PolicyCredentialSecurityEventDocument.class)).isEqualTo(1);
    }

    @Test
    void additionalCredentialNeedsIndependentGrantAndCompromisedKeyCannotRecover() throws Exception {
        Authenticator first = register(author());
        String firstGrant = grant(author(), PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST);
        assertThatThrownBy(() -> webAuthn.startRegistration(author(),
                new PolicyWebAuthnService.RegistrationIntent(firstGrant)))
                .isInstanceOf(AccessDeniedException.class);
        String revokeGrant = grant(author(), PolicyCredentialEnrollmentGrantDocument.Purpose.COMPROMISE_REVOCATION);
        webAuthn.protectedCredentialRevocation(author(), first.credentialId,
                new PolicyWebAuthnService.ProtectedRevocation(revokeGrant, true));
        assertThatThrownBy(() -> webAuthn.startCredentialRevocation(author(), first.credentialId,
                new PolicyWebAuthnService.CredentialActionIntent("compromised-revoke")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(mongo.findById(first.credentialId, PolicyWebAuthnCredential.class).status())
                .isEqualTo("COMPROMISED");
        String recoveryGrant = grant(author(), PolicyCredentialEnrollmentGrantDocument.Purpose.COMPROMISE_RECOVERY);
        var recovered = webAuthn.startRegistration(author(),
                new PolicyWebAuthnService.RegistrationIntent(recoveryGrant));
        assertThat(recovered.challengeId()).startsWith("registration:");
        assertThat(mongo.findById(first.credentialId, PolicyWebAuthnCredential.class).revoked()).isTrue();
    }

    @Test
    void lostCredentialCannotSignAndUsedChallengesSurviveUnusedRetention() throws Exception {
        Authenticator key = register(author());
        PolicyProposalDocument proposal = draft();
        var pending = webAuthn.startAction(author(), proposal.id(),
                new PolicyWebAuthnService.ActionIntent("lost-key-action", 0, null, null), "submit");
        String expiredUnusedId = pending.challengeId();
        String token = grant(author(), PolicyCredentialEnrollmentGrantDocument.Purpose.LOST_DEVICE_REVOCATION);
        webAuthn.protectedCredentialRevocation(author(), key.credentialId,
                new PolicyWebAuthnService.ProtectedRevocation(token, false));
        assertThat(mongo.findById(key.credentialId, PolicyWebAuthnCredential.class).status())
                .isEqualTo("LOST");
        assertThatThrownBy(() -> webAuthn.finishAction(author(), proposal.id(),
                new PolicyWebAuthnService.FinishAction("lost-key-action", pending.challengeId(),
                        key.assertion(pending.challengeId(), ORIGIN, "localhost", true, 1)), "submit"))
                .isInstanceOf(RuntimeException.class);

        clock.set(START.plusSeconds(8 * 24 * 3600));
        webAuthn.pruneExpiredUnusedChallenges();
        assertThat(mongo.findById(expiredUnusedId, PolicyWebAuthnChallenge.class)).isNull();
        assertThat(mongo.count(Query.query(Criteria.where("used").is(true)),
                PolicyWebAuthnChallenge.class)).isGreaterThan(0);
    }

    @Test
    void trustChangeRequiresTwoIndependentPasskeysAndNeverActivatesRegistry() throws Exception {
        var requester = actor("trust-requester", Set.of(ModelPolicyAuthority.CREDENTIAL_ENROLL,
                ModelPolicyAuthority.TRUST_REQUEST, ModelPolicyAuthority.PRODUCTION));
        var reviewer = actor("trust-reviewer", Set.of(ModelPolicyAuthority.CREDENTIAL_ENROLL,
                ModelPolicyAuthority.TRUST_APPROVE, ModelPolicyAuthority.TRUST_READ,
                ModelPolicyAuthority.PRODUCTION));
        Authenticator requesterKey = register(requester);
        Authenticator reviewerKey = register(reviewer);
        var registries = org.mockito.Mockito.mock(
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.class);
        var baseline = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.RegistryView(
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                "READY", 1L, "a".repeat(64), 1, List.of());
        org.mockito.Mockito.when(registries.requireReady(
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS))
                .thenReturn(baseline);
        org.mockito.Mockito.when(registries.snapshot(
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS))
                .thenReturn(baseline);
        var changes = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService(
                mongo, new TransactionTemplate(new MongoTransactionManager(factory)), webAuthn,
                registries, new ObjectMapper(), clock);
        var intent = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Intent(
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Action.GRANT,
                actor("new-approver", Set.of()).id(), null, null, null,
                List.of("POLICY_APPROVER"), ModelGovernanceEnvironment.TEST,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 2, "b".repeat(64), "case-trust-1");
        var start = changes.startRequest(requester, "trust-op-1", intent);
        var requested = changes.finishRequest(requester, "trust-op-1", intent, start.challengeId(),
                requesterKey.assertion(start.challengeId(), ORIGIN, "localhost", true, 1));
        assertThat(requested.status()).isEqualTo("PENDING_REVIEW");
        assertThatThrownBy(() -> changes.startReview(requester, requested.id(), "self-review",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE))
                .isInstanceOf(AccessDeniedException.class);
        var review = changes.startReview(reviewer, requested.id(), "trust-review-1",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE);
        var approved = changes.finishReview(reviewer, requested.id(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE,
                review.challengeId(), reviewerKey.assertion(review.challengeId(), ORIGIN, "localhost", true, 1));
        assertThat(approved.status()).isEqualTo("PENDING_OPERATOR_ACTION");
        assertThat(approved.intent().candidateRegistrySha256()).isEqualTo("b".repeat(64));
        assertThat(changes.deployment(reviewer, approved.id()).rolloutState())
                .isEqualTo("NO_ACKNOWLEDGEMENT");
        mongo.save(new com.frauddetection.alert.governance.modelpromotion.PolicyTrustInstanceObservation(
                "ALERT_VERIFIER:instance-a:ONLINE_ACTORS", "ALERT_VERIFIER", "instance-a",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                2, "b".repeat(64), clock.instant()));
        assertThat(changes.deployment(reviewer, approved.id()).rolloutState())
                .isEqualTo("PARTIAL_ROLLOUT");
        mongo.save(new com.frauddetection.alert.governance.modelpromotion.PolicyTrustInstanceObservation(
                "PUBLISHER:instance-b:ONLINE_ACTORS", "PUBLISHER", "instance-b",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                1, "a".repeat(64), clock.instant()));
        assertThat(changes.deployment(reviewer, approved.id()).rolloutState())
                .isEqualTo("MISMATCH_OR_PARTIAL_ROLLOUT");
        mongo.save(new com.frauddetection.alert.governance.modelpromotion.PolicyTrustInstanceObservation(
                "PUBLISHER:instance-b:ONLINE_ACTORS", "PUBLISHER", "instance-b",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                2, "b".repeat(64), clock.instant()));
        var observed = changes.deployment(reviewer, approved.id());
        assertThat(observed.rolloutState()).isEqualTo("OBSERVED_CONSISTENT_PENDING_OPERATOR_CONFIRMATION");
        assertThat(observed.consistentObservedGeneration()).isEqualTo(2L);
        assertThat(observed.consistentObservedRegistrySha256()).isEqualTo("b".repeat(64));
        assertThat(observed.observationSetTruncated()).isFalse();
        assertThat(mongo.count(new Query(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeAuditDocument.class))
                .isEqualTo(2);
        assertThat(mongo.count(new Query(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeDocument.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentGrantUseAfterServiceRestartEnrollsAtMostOneCredential() throws Exception {
        String token = grant(author(), PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST);
        var firstChallenge = webAuthn.startRegistration(author(),
                new PolicyWebAuthnService.RegistrationIntent(token));
        var secondChallenge = webAuthn.startRegistration(author(),
                new PolicyWebAuthnService.RegistrationIntent(token));
        Authenticator firstKey = new Authenticator(author());
        firstKey.credentialId = firstKey.newCredentialId();
        Authenticator secondKey = new Authenticator(author());
        secondKey.credentialId = secondKey.newCredentialId();
        var firstRequest = PublicKeyCredentialCreationOptions.fromJson(mongo.findById(
                firstChallenge.challengeId(), PolicyWebAuthnChallenge.class).requestJson());
        var secondRequest = PublicKeyCredentialCreationOptions.fromJson(mongo.findById(
                secondChallenge.challengeId(), PolicyWebAuthnChallenge.class).requestJson());
        var firstCommand = new PolicyWebAuthnService.FinishCeremony(firstChallenge.challengeId(),
                firstKey.registration(firstRequest.getChallenge().getBase64Url()), token);
        var secondCommand = new PolicyWebAuthnService.FinishCeremony(secondChallenge.challengeId(),
                secondKey.registration(secondRequest.getChallenge().getBase64Url()), token);
        var manager = new MongoTransactionManager(factory);
        var store = new MongoPolicyGovernanceStore(mongo, new TransactionTemplate(manager), clock);
        var reopenedGrants = new PolicyCredentialEnrollmentGrantService(mongo, manager, store);
        var reopened = new PolicyWebAuthnService(mongo, manager,
                new PolicyWebAuthnCredentialRepository(mongo), governance, store, reopenedGrants,
                new ObjectMapper(), new MockEnvironment(), "localhost", ORIGIN);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> finishRegistrationInRace(reopened, ready, go, firstCommand));
            var two = executor.submit(() -> finishRegistrationInRace(reopened, ready, go, secondCommand));
            ready.await();
            go.countDown();
            List<Object> results = List.of(one.get(), two.get());
            assertThat(results.stream().filter(PolicyWebAuthnService.CredentialSummary.class::isInstance).count())
                    .isEqualTo(1);
        }
        assertThat(mongo.count(new Query(), PolicyWebAuthnCredential.class)).isEqualTo(1);
        assertThat(mongo.count(Query.query(Criteria.where("action").is("CREDENTIAL_ENROLLED")),
                PolicyCredentialSecurityEventDocument.class)).isEqualTo(1);
    }

    @Test
    void sharedBoundAssertionRejectsChangedModelContractAndCrossDomainReplay() throws Exception {
        Authenticator key = register(author());
        Map<String, Object> contract = Map.of("domain", "com.frauddetection.model-promotion.review.v1",
                "proposalId", "proposal-1", "modelArtifactSha256", "a".repeat(64), "decision", "APPROVED");
        var options = webAuthn.startBoundAssertion(author(), "model-promotion-review", "review-1", contract);
        String challenge = AssertionRequest.fromJson(mongo.findById(options.challengeId(),
                GovernanceWebAuthnBoundChallenge.class).requestJson())
                .getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url();
        String assertion = key.assertionWithChallenge(challenge, ORIGIN, "localhost", true, 1);
        var transaction = new TransactionTemplate(new MongoTransactionManager(factory));
        assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(
                author(), "model-promotion-review", options.challengeId(), assertion,
                Map.of("domain", "com.frauddetection.model-promotion.review.v1",
                        "proposalId", "proposal-1", "modelArtifactSha256", "b".repeat(64),
                        "decision", "APPROVED"))))
                .isInstanceOf(PolicyGovernanceConflictException.class);
        assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(
                approver(), "model-promotion-review", options.challengeId(), assertion, contract)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(
                author(), "policy-action", options.challengeId(), assertion, contract)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(
                author(), "model-promotion-review", options.challengeId(), assertion,
                Map.of("domain", "com.frauddetection.model-promotion.review.v1",
                        "proposalId", "proposal-1", "modelArtifactSha256", "a".repeat(64),
                        "decision", "REJECTED"))))
                .isInstanceOf(PolicyGovernanceConflictException.class);
        var proof = transaction.execute(ignored -> webAuthn.acceptBoundAssertion(author(),
                "model-promotion-review", options.challengeId(), assertion, contract));
        assertThat(proof.credentialId()).isEqualTo(key.credentialId);
        assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(author(),
                "model-promotion-review", options.challengeId(), assertion, contract)))
                .isInstanceOf(AccessDeniedException.class);
    }

    private Object finishRegistrationInRace(PolicyWebAuthnService service, CountDownLatch ready,
            CountDownLatch go, PolicyWebAuthnService.FinishCeremony command) {
        ready.countDown();
        try {
            go.await();
            return service.finishRegistration(author(), command);
        } catch (Exception failure) {
            return failure;
        }
    }

    @Test
    void genuineAssertionsBindSubmitAndApprovalAndRecoverLostResponse() throws Exception {
        Authenticator authorKey = register(author());
        Authenticator approverKey = register(approver());
        PolicyProposalDocument draft = draft();

        var submit = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-submit", 0, null, null), "submit");
        assertThat(json.readTree(submit.optionsJson()).has("publicKey")).isTrue();
        String signedSubmit = authorKey.assertion(submit.challengeId(), ORIGIN, "localhost", true, 1);
        var submitCommand = new PolicyWebAuthnService.FinishAction("proof-submit", submit.challengeId(), signedSubmit);
        PolicyProposalDocument submitted = webAuthn.finishAction(author(), draft.id(), submitCommand, "submit");
        assertThat(submitted.status()).isEqualTo(PolicyGovernanceState.SUBMITTED);
        assertThat(mongo.findById(submit.challengeId(), PolicyWebAuthnEvidence.class).acceptedAt())
                .isEqualTo(submitted.submittedAt());
        assertThat(mongo.findById(submit.challengeId(), PolicyWebAuthnEvidence.class).assertionResponseJson())
                .isEqualTo(signedSubmit);
        assertThat(webAuthn.finishAction(author(), draft.id(), submitCommand, "submit"))
                .isEqualTo(submitted);
        PolicyGovernanceActor revokedSubmitMandate = new PolicyGovernanceActor(author().id(), Set.of(),
                author().issuer(), author().subject());
        assertThatThrownBy(() -> webAuthn.finishAction(revokedSubmitMandate, draft.id(), submitCommand,
                "submit")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-submit", submit.challengeId(), "{}"), "submit"))
                .isInstanceOf(PolicyGovernanceConflictException.class);

        clock.set(START.plusSeconds(45));
        var approve = webAuthn.startAction(approver(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-approve", 1, ReviewDecision.APPROVE,
                        "Risk committee accepted the frozen thresholds"), "review");
        assertThat(mongo.findById(approve.challengeId(), PolicyWebAuthnChallenge.class).rationale())
                .isEqualTo("Risk committee accepted the frozen thresholds");
        String signedApproval = approverKey.assertion(approve.challengeId(), ORIGIN, "localhost", true, 1);
        PolicyProposalDocument approved = webAuthn.finishAction(approver(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-approve", approve.challengeId(), signedApproval),
                "review");
        assertThat(approved.status()).isEqualTo(PolicyGovernanceState.APPROVED);
        assertThat(approved.approvalEvidenceReference()).isEqualTo("webauthn:" + approve.challengeId());
        assertThat(approved.decidedAt()).isEqualTo(START.plusSeconds(45));
        assertThat(mongo.findById(approve.challengeId(), PolicyWebAuthnEvidence.class).acceptedAt())
                .isEqualTo(approved.decidedAt());
        assertThat(mongo.count(new Query(), PolicyWebAuthnEvidence.class)).isEqualTo(2);
        PolicyProposalDocument pending = governance.requestPublication(
                actor("publisher", Set.of(ModelPolicyAuthority.PUBLISH_REQUEST)), approved.id(),
                new TransitionCommand("proof-publication-request", 2));
        assertThat(pending.status()).isEqualTo(PolicyGovernanceState.PUBLICATION_PENDING);
        assertThat(pending.status()).isNotEqualTo(PolicyGovernanceState.PUBLISHED);
        PolicyPublicationJobDocument job = mongo.findById(pending.publicationRequestId(),
                PolicyPublicationJobDocument.class);
        assertThat(job).isNotNull();
        assertThat(job.state()).isEqualTo(PolicyPublicationJobDocument.State.PENDING);
        assertThat(job.draft()).isEqualTo(approved.draft());
        assertThat(job.draftSha256()).isEqualTo(approved.draftSha256());
        assertThat(job.submissionEvidenceReference()).isEqualTo("webauthn:" + submit.challengeId());
        assertThat(job.approvalEvidenceReference()).isEqualTo(approved.approvalEvidenceReference());
        assertThat(job.submittedAt()).isEqualTo(submitted.submittedAt());
        assertThat(job.decidedAt()).isEqualTo(approved.decidedAt());
        assertThat(governance.requestPublication(actor("publisher", Set.of(ModelPolicyAuthority.PUBLISH_REQUEST)),
                approved.id(), new TransitionCommand("proof-publication-request", 2))).isEqualTo(pending);
        assertThat(mongo.count(new Query(), PolicyPublicationJobDocument.class)).isEqualTo(1);
    }

    @Test
    void committedActionReceiptReplaysAfterOrdinaryCredentialRevocation() throws Exception {
        Authenticator key = register(author());
        PolicyProposalDocument draft = draft();
        var challenge = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("receipt-before-revocation", 0, null, null), "submit");
        var command = new PolicyWebAuthnService.FinishAction("receipt-before-revocation",
                challenge.challengeId(), key.assertion(challenge.challengeId(), ORIGIN, "localhost", true, 1));
        PolicyProposalDocument committed = webAuthn.finishAction(author(), draft.id(), command, "submit");
        var revoke = webAuthn.startCredentialRevocation(author(), key.credentialId,
                new PolicyWebAuthnService.CredentialActionIntent("revoke-after-receipt"));
        webAuthn.finishCredentialRevocation(author(), key.credentialId,
                new PolicyWebAuthnService.FinishAction("revoke-after-receipt", revoke.challengeId(),
                        key.assertion(revoke.challengeId(), ORIGIN, "localhost", true, 2)));
        assertThat(webAuthn.finishAction(author(), draft.id(), command, "submit")).isEqualTo(committed);
        assertThat(governance.get(author(), draft.id()).version()).isEqualTo(1);
    }

    @Test
    void malformedAssertionWrongRpOriginChallengeOrMissingUvNeverConsumesChallenge() throws Exception {
        Authenticator key = register(author());
        Authenticator forgedKey = new Authenticator(author());
        forgedKey.credentialId = key.credentialId;
        PolicyProposalDocument draft = draft();
        var options = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-negative", 0, null, null), "submit");
        for (String assertion : List.of(
                key.assertion(options.challengeId(), "http://evil.example", "localhost", true, 1),
                key.assertion(options.challengeId(), ORIGIN, "evil.example", true, 1),
                key.assertion(options.challengeId(), ORIGIN, "localhost", false, 1),
                key.assertionWithChallenge("wrong-challenge", ORIGIN, "localhost", true, 1),
                forgedKey.assertion(options.challengeId(), ORIGIN, "localhost", true, 1))) {
            assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                    new PolicyWebAuthnService.FinishAction("proof-negative", options.challengeId(), assertion),
                    "submit")).isInstanceOf(RuntimeException.class);
        }
        assertThat(mongo.findById(options.challengeId(), PolicyWebAuthnChallenge.class).used()).isFalse();
        assertThat(governance.get(author(), draft.id()).status()).isEqualTo(PolicyGovernanceState.DRAFT);
    }

    @Test
    void revokedCredentialAndRevokedMandateBlockDecision() throws Exception {
        Authenticator key = register(author());
        PolicyProposalDocument draft = draft();
        var options = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-revoked", 0, null, null), "submit");
        String assertion = key.assertion(options.challengeId(), ORIGIN, "localhost", true, 1);
        PolicyGovernanceActor noMandate = new PolicyGovernanceActor(author().id(), Set.of(),
                author().issuer(), author().subject());
        assertThatThrownBy(() -> webAuthn.finishAction(noMandate, draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-revoked", options.challengeId(), assertion),
                "submit")).isInstanceOf(AccessDeniedException.class);
        var revoke = webAuthn.startCredentialRevocation(author(), key.credentialId,
                new PolicyWebAuthnService.CredentialActionIntent("revoke-proof-revoked"));
        webAuthn.finishCredentialRevocation(author(), key.credentialId,
                new PolicyWebAuthnService.FinishAction("revoke-proof-revoked", revoke.challengeId(),
                        key.assertion(revoke.challengeId(), ORIGIN, "localhost", true, 1)));
        assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-revoked", options.challengeId(), assertion),
                "submit")).isInstanceOf(RuntimeException.class);
        assertThat(governance.get(author(), draft.id()).status()).isEqualTo(PolicyGovernanceState.DRAFT);
    }

    @Test
    void validCredentialCannotProveAnotherActorOrBecomeAutomaticApproval() throws Exception {
        Authenticator authorKey = register(author());
        Authenticator approverKey = register(approver());
        PolicyProposalDocument draft = draft();
        var options = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-owner", 0, null, null), "submit");
        String alienAssertion = approverKey.assertion(options.challengeId(), ORIGIN, "localhost", true, 1);
        assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-owner", options.challengeId(), alienAssertion),
                "submit")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-owner", options.challengeId(), null),
                "submit")).isInstanceOf(IllegalArgumentException.class);
        String authorAssertion = authorKey.assertion(options.challengeId(), ORIGIN, "localhost", true, 1);
        webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-owner", options.challengeId(), authorAssertion),
                "submit");
        PolicyGovernanceActor selfApprover = new PolicyGovernanceActor(author().id(),
                Set.of(ModelPolicyAuthority.APPROVE), author().issuer(), author().subject());
        assertThatThrownBy(() -> webAuthn.startAction(selfApprover, draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-self", 1, ReviewDecision.APPROVE,
                        "Attempted self-review"), "review"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sameCredentialIdCannotBeEnrolledForAnotherOidcActor() throws Exception {
        Authenticator first = register(author());
        String token = grant(approver(), PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST);
        var second = webAuthn.startRegistration(approver(), new PolicyWebAuthnService.RegistrationIntent(token));
        var request = PublicKeyCredentialCreationOptions.fromJson(mongo.findById(second.challengeId(),
                PolicyWebAuthnChallenge.class).requestJson());
        assertThatThrownBy(() -> webAuthn.finishRegistration(approver(),
                new PolicyWebAuthnService.FinishCeremony(second.challengeId(),
                        first.registration(request.getChallenge().getBase64Url()), token)))
                .isInstanceOf(RuntimeException.class);
        assertThat(mongo.count(new Query(), PolicyWebAuthnCredential.class)).isEqualTo(1);
    }

    @Test
    void modifiedDecisionAndCounterRollbackFailClosed() throws Exception {
        Authenticator authorKey = register(author());
        Authenticator approverKey = register(approver());
        PolicyProposalDocument draft = draft();
        var submit = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-submit-counter", 0, null, null), "submit");
        webAuthn.finishAction(author(), draft.id(), new PolicyWebAuthnService.FinishAction(
                "proof-submit-counter", submit.challengeId(),
                authorKey.assertion(submit.challengeId(), ORIGIN, "localhost", true, 1)), "submit");
        var review = webAuthn.startAction(approver(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-review-tampered", 1, ReviewDecision.APPROVE,
                        "Independent review"), "review");
        String approval = approverKey.assertion(review.challengeId(), ORIGIN, "localhost", true, 1);
        PolicyGovernanceActor revokedApprovalMandate = new PolicyGovernanceActor(approver().id(), Set.of(),
                approver().issuer(), approver().subject());
        assertThatThrownBy(() -> webAuthn.finishAction(revokedApprovalMandate, draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-review-tampered", review.challengeId(), approval),
                "review")).isInstanceOf(AccessDeniedException.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(review.challengeId())),
                new Update().set("decision", "REJECTED"), PolicyWebAuthnChallenge.class);
        assertThatThrownBy(() -> webAuthn.finishAction(approver(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-review-tampered", review.challengeId(), approval),
                "review")).isInstanceOf(PolicyGovernanceConflictException.class);

        PolicyProposalDocument anotherDraft = draft();
        var next = webAuthn.startAction(author(), anotherDraft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-counter-reuse", 0, null, null), "submit");
        assertThatThrownBy(() -> webAuthn.finishAction(author(), anotherDraft.id(),
                new PolicyWebAuthnService.FinishAction("proof-counter-reuse", next.challengeId(),
                        authorKey.assertion(next.challengeId(), ORIGIN, "localhost", true, 1)), "submit"))
                .isInstanceOf(RuntimeException.class);
        assertThat(governance.get(author(), anotherDraft.id()).status()).isEqualTo(PolicyGovernanceState.DRAFT);
    }

    @Test
    void concurrentUseOfOneChallengeHasOneDurableDecision() throws Exception {
        Authenticator key = register(author());
        PolicyProposalDocument draft = draft();
        var options = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-concurrent", 0, null, null), "submit");
        var command = new PolicyWebAuthnService.FinishAction("proof-concurrent", options.challengeId(),
                key.assertion(options.challengeId(), ORIGIN, "localhost", true, 1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> finishInRace(ready, go, draft.id(), command));
            var second = executor.submit(() -> finishInRace(ready, go, draft.id(), command));
            ready.await();
            go.countDown();
            List<Object> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(PolicyProposalDocument.class::isInstance).count())
                    .isGreaterThanOrEqualTo(1);
        }
        assertThat(governance.get(author(), draft.id()).status()).isEqualTo(PolicyGovernanceState.SUBMITTED);
        assertThat(mongo.count(new Query(), PolicyWebAuthnEvidence.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), PolicyGovernanceHistoryDocument.class)).isEqualTo(2);
    }

    private Object finishInRace(CountDownLatch ready, CountDownLatch go, String id,
            PolicyWebAuthnService.FinishAction command) {
        ready.countDown();
        try {
            go.await();
            return webAuthn.finishAction(author(), id, command, "submit");
        } catch (Exception failure) {
            return failure;
        }
    }

    @Test
    void expiredOrChangedPolicySnapshotBlocksValidSignature() throws Exception {
        Authenticator key = register(author());
        PolicyProposalDocument draft = draft();
        var expired = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-expired", 0, null, null), "submit");
        String assertion = key.assertion(expired.challengeId(), ORIGIN, "localhost", true, 1);
        clock.set(START.plusSeconds(121));
        assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-expired", expired.challengeId(), assertion),
                "submit")).isInstanceOf(PolicyGovernanceConflictException.class);
        clock.set(START);
        var changed = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-changed", 0, null, null), "submit");
        String signed = key.assertion(changed.challengeId(), ORIGIN, "localhost", true, 1);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(draft.id())),
                new Update().set("draft.governanceScope.environment", "PRODUCTION"),
                PolicyProposalDocument.class);
        assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-changed", changed.challengeId(), signed),
                "submit")).isInstanceOf(RuntimeException.class);

        PolicyProposalDocument second = draft();
        var digestChanged = webAuthn.startAction(author(), second.id(),
                new PolicyWebAuthnService.ActionIntent("proof-digest-changed", 0, null, null), "submit");
        String digestAssertion = key.assertion(digestChanged.challengeId(), ORIGIN, "localhost", true, 1);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(digestChanged.challengeId())),
                new Update().set("draftSha256", "0".repeat(64)), PolicyWebAuthnChallenge.class);
        assertThatThrownBy(() -> webAuthn.finishAction(author(), second.id(),
                new PolicyWebAuthnService.FinishAction("proof-digest-changed", digestChanged.challengeId(),
                        digestAssertion), "submit"))
                .isInstanceOf(PolicyGovernanceConflictException.class);
    }

    private Authenticator register(PolicyGovernanceActor actor) throws Exception {
        Authenticator authenticator = new Authenticator(actor);
        String token = grant(actor, PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST);
        var options = webAuthn.startRegistration(actor, new PolicyWebAuthnService.RegistrationIntent(token));
        assertThat(json.readTree(options.optionsJson()).has("publicKey")).isTrue();
        var request = PublicKeyCredentialCreationOptions.fromJson(mongo.findById(options.challengeId(),
                PolicyWebAuthnChallenge.class).requestJson());
        authenticator.credentialId = authenticator.newCredentialId();
        String response = authenticator.registration(request.getChallenge().getBase64Url());
        webAuthn.finishRegistration(actor,
                new PolicyWebAuthnService.FinishCeremony(options.challengeId(), response, token));
        return authenticator;
    }

    private String grant(PolicyGovernanceActor target, PolicyCredentialEnrollmentGrantDocument.Purpose purpose) {
        String id = "enrollment-" + UUID.randomUUID();
        grants.request(credentialAdmin("security-operator-a"),
                new PolicyCredentialEnrollmentGrantService.GrantRequest(id, target.issuer(), target.subject(),
                        purpose, "case-" + id));
        return grants.approve(credentialAdmin("security-operator-b"), id).token();
    }

    private PolicyGovernanceActor credentialAdmin(String subject) {
        return actor(subject, Set.of(ModelPolicyAuthority.CREDENTIAL_ADMIN));
    }

    private PolicyProposalDocument draft() {
        List<ModelPromotionPolicy.CriterionRule> rules = Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(Collectors.toMap(
                                key -> key, key -> BigDecimal.ONE)), 86400, "VERIFIED",
                        criterion.evidenceSource(), criterion.metricSemantics(), criterion.populationDefinition(),
                        criterion.denominatorDefinition(), null)).toList();
        var input = new DraftInput("policy-" + UUID.randomUUID(), 1,
                new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, ModelGovernanceEnvironment.TEST),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, approver().id(), "review-1",
                "2026-12-01T00:00:00Z", "2027-01-01T00:00:00Z", rules);
        return governance.create(author(), new DraftCommand("create-" + UUID.randomUUID(), input));
    }

    private PolicyGovernanceActor author() { return actor("author", Set.of(ModelPolicyAuthority.CREATE,
            ModelPolicyAuthority.READ, ModelPolicyAuthority.SUBMIT, ModelPolicyAuthority.CREDENTIAL_ENROLL)); }
    private PolicyGovernanceActor approver() { return actor("approver", Set.of(ModelPolicyAuthority.READ,
            ModelPolicyAuthority.APPROVE, ModelPolicyAuthority.REJECT, ModelPolicyAuthority.CREDENTIAL_ENROLL)); }

    private PolicyGovernanceActor actor(String subject, Set<String> grants) {
        try {
            String issuer = "https://issuer.example";
            String hex = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((issuer + "#" + subject).getBytes(StandardCharsets.UTF_8)));
            return new PolicyGovernanceActor("oidc:" + hex, grants, issuer, subject);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private final class Authenticator {
        private final KeyPair keyPair;
        private final PolicyGovernanceActor actor;
        private String credentialId;

        Authenticator(PolicyGovernanceActor actor) throws Exception {
            this.actor = actor;
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            keyPair = generator.generateKeyPair();
        }

        String newCredentialId() {
            return encode(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        }

        String registration(String challenge) throws Exception {
            byte[] id = Base64.getUrlDecoder().decode(credentialId);
            ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
            CBORObject cose = CBORObject.NewMap().Add(1, 2).Add(3, -7).Add(-1, 1)
                    .Add(-2, fixed(publicKey.getW().getAffineX())).Add(-3, fixed(publicKey.getW().getAffineY()));
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(sha("localhost".getBytes(StandardCharsets.UTF_8)));
            auth.write(0x45);
            auth.write(new byte[4]);
            auth.write(new byte[16]);
            auth.write(ByteBuffer.allocate(2).putShort((short) id.length).array());
            auth.write(id);
            auth.write(cose.EncodeToBytes());
            CBORObject attestation = CBORObject.NewMap().Add("fmt", "none")
                    .Add("attStmt", CBORObject.NewMap()).Add("authData", auth.toByteArray());
            return json.writeValueAsString(Map.of("id", credentialId, "rawId", credentialId,
                    "type", "public-key", "response", Map.of(
                            "clientDataJSON", encode(clientData("webauthn.create", challenge, ORIGIN)),
                            "attestationObject", encode(attestation.EncodeToBytes())),
                    "clientExtensionResults", Map.of()));
        }

        String assertion(String challengeId, String origin, String rpId, boolean uv, long counter)
                throws Exception {
            var stored = mongo.findById(challengeId, PolicyWebAuthnChallenge.class);
            var bound = stored == null ? mongo.findById(challengeId, GovernanceWebAuthnBoundChallenge.class) : null;
            String requestJson = stored != null ? stored.requestJson() : bound.requestJson();
            String challenge = AssertionRequest.fromJson(requestJson)
                    .getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url();
            return assertionWithChallenge(challenge, origin, rpId, uv, counter);
        }

        String assertionWithChallenge(String challenge, String origin, String rpId, boolean uv,
                long counter) throws Exception {
            byte[] clientData = clientData("webauthn.get", challenge, origin);
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(sha(rpId.getBytes(StandardCharsets.UTF_8)));
            auth.write(uv ? 0x05 : 0x01);
            auth.write(ByteBuffer.allocate(4).putInt((int) counter).array());
            byte[] authenticatorData = auth.toByteArray();
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(authenticatorData);
            signature.update(sha(clientData));
            byte[] signed = signature.sign();
            return json.writeValueAsString(Map.of("id", credentialId, "rawId", credentialId,
                    "type", "public-key", "response", Map.of(
                            "clientDataJSON", encode(clientData),
                            "authenticatorData", encode(authenticatorData),
                            "signature", encode(signed),
                            "userHandle", encode(java.util.HexFormat.of().parseHex(actor.id().substring(5)))),
                    "clientExtensionResults", Map.of()));
        }

        private byte[] clientData(String type, String challenge, String origin) throws Exception {
            return json.writeValueAsBytes(Map.of("type", type, "challenge", challenge,
                    "origin", origin, "crossOrigin", false));
        }

        private byte[] fixed(java.math.BigInteger value) {
            byte[] encoded = value.toByteArray();
            byte[] out = new byte[32];
            System.arraycopy(encoded, Math.max(0, encoded.length - 32), out,
                    Math.max(0, 32 - encoded.length), Math.min(32, encoded.length));
            return out;
        }

        private byte[] sha(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }
        private String encode(byte[] data) { return Base64.getUrlEncoder().withoutPadding().encodeToString(data); }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant value) { instant = new AtomicReference<>(value); }
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
