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
                mapper, "publisher-c", "publisher-key", "issuer-d", "issuer-key");
        PolicyWebAuthnCredentialRepository credentials = new PolicyWebAuthnCredentialRepository(mongo);
        credentials.ensureIndexes();
        Environment environment = new MockEnvironment();
        webAuthn = new PolicyWebAuthnService(mongo, manager, credentials, governance, governanceStore,
                mapper, environment, "localhost", ORIGIN);
        webAuthn.ensureIndexes();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongo != null) mongo.getDb().drop();
        if (factory != null) factory.destroy();
    }

    @Test
    void genuineAssertionsBindSubmitAndApprovalAndRecoverLostResponse() throws Exception {
        Authenticator authorKey = register(author());
        Authenticator approverKey = register(approver());
        PolicyProposalDocument draft = draft();

        var submit = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-submit", 0, null), "submit");
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
    void malformedAssertionWrongRpOriginChallengeOrMissingUvNeverConsumesChallenge() throws Exception {
        Authenticator key = register(author());
        Authenticator forgedKey = new Authenticator(author());
        forgedKey.credentialId = key.credentialId;
        PolicyProposalDocument draft = draft();
        var options = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-negative", 0, null), "submit");
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
                new PolicyWebAuthnService.ActionIntent("proof-revoked", 0, null), "submit");
        String assertion = key.assertion(options.challengeId(), ORIGIN, "localhost", true, 1);
        PolicyGovernanceActor noMandate = new PolicyGovernanceActor(author().id(), Set.of(),
                author().issuer(), author().subject());
        assertThatThrownBy(() -> webAuthn.finishAction(noMandate, draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-revoked", options.challengeId(), assertion),
                "submit")).isInstanceOf(AccessDeniedException.class);
        webAuthn.revoke(author(), key.credentialId);
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
                new PolicyWebAuthnService.ActionIntent("proof-owner", 0, null), "submit");
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
        var second = webAuthn.startRegistration(approver());
        var request = PublicKeyCredentialCreationOptions.fromJson(mongo.findById(second.challengeId(),
                PolicyWebAuthnChallenge.class).requestJson());
        assertThatThrownBy(() -> webAuthn.finishRegistration(approver(),
                new PolicyWebAuthnService.FinishCeremony(second.challengeId(),
                        first.registration(request.getChallenge().getBase64Url()))))
                .isInstanceOf(RuntimeException.class);
        assertThat(mongo.count(new Query(), PolicyWebAuthnCredential.class)).isEqualTo(1);
    }

    @Test
    void modifiedDecisionAndCounterRollbackFailClosed() throws Exception {
        Authenticator authorKey = register(author());
        Authenticator approverKey = register(approver());
        PolicyProposalDocument draft = draft();
        var submit = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-submit-counter", 0, null), "submit");
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
                new PolicyWebAuthnService.ActionIntent("proof-counter-reuse", 0, null), "submit");
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
                new PolicyWebAuthnService.ActionIntent("proof-concurrent", 0, null), "submit");
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
                new PolicyWebAuthnService.ActionIntent("proof-expired", 0, null), "submit");
        String assertion = key.assertion(expired.challengeId(), ORIGIN, "localhost", true, 1);
        clock.set(START.plusSeconds(121));
        assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-expired", expired.challengeId(), assertion),
                "submit")).isInstanceOf(PolicyGovernanceConflictException.class);
        clock.set(START);
        var changed = webAuthn.startAction(author(), draft.id(),
                new PolicyWebAuthnService.ActionIntent("proof-changed", 0, null), "submit");
        String signed = key.assertion(changed.challengeId(), ORIGIN, "localhost", true, 1);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(draft.id())),
                new Update().set("draft.governanceScope.environment", "PRODUCTION"),
                PolicyProposalDocument.class);
        assertThatThrownBy(() -> webAuthn.finishAction(author(), draft.id(),
                new PolicyWebAuthnService.FinishAction("proof-changed", changed.challengeId(), signed),
                "submit")).isInstanceOf(RuntimeException.class);

        PolicyProposalDocument second = draft();
        var digestChanged = webAuthn.startAction(author(), second.id(),
                new PolicyWebAuthnService.ActionIntent("proof-digest-changed", 0, null), "submit");
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
        var options = webAuthn.startRegistration(actor);
        assertThat(json.readTree(options.optionsJson()).has("publicKey")).isTrue();
        var request = PublicKeyCredentialCreationOptions.fromJson(mongo.findById(options.challengeId(),
                PolicyWebAuthnChallenge.class).requestJson());
        authenticator.credentialId = authenticator.newCredentialId();
        String response = authenticator.registration(request.getChallenge().getBase64Url());
        webAuthn.finishRegistration(actor,
                new PolicyWebAuthnService.FinishCeremony(options.challengeId(), response));
        return authenticator;
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
            String challenge = AssertionRequest.fromJson(stored.requestJson())
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
