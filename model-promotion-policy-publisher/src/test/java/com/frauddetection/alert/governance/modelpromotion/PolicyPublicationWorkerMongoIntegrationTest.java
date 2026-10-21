package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.MongoPolicyGovernanceStore;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceState;
import com.frauddetection.alert.governance.modelpolicy.PolicyProposalDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationStatusController;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnChallenge;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnCredential;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnCredentialRepository;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.upokecenter.cbor.CBORObject;
import com.yubico.webauthn.AssertionRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyPublicationWorkerMongoIntegrationTest {
    private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
    private static final String ORIGIN = "http://localhost:4173";
    private final ObjectMapper mapper = new ObjectMapper();
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private SimpleMongoClientDatabaseFactory factory;
    private MongoTemplate mongo;
    private TransactionTemplate transactions;
    private PolicyGovernanceService governance;
    private PolicyWebAuthnService webAuthn;
    private MutableClock clock;
    private KeyPair publisherKey;
    private KeyPair issuerKey;
    private Ed25519ModelPromotionPolicyAuthorityVerifier issuerVerifier;
    private OnlinePolicyActorTrustRegistry actors;
    private InternalPolicyApprovalTrustRegistry approvalTrust;
    private PolicyGovernanceActor author;
    private PolicyGovernanceActor approver;

    @BeforeAll static void startMongo() { MONGO.start(); }
    @AfterAll static void stopMongo() { MONGO.stop(); }

    @BeforeEach void setUp() throws Exception {
        factory = new SimpleMongoClientDatabaseFactory(MONGO.getReplicaSetUrl("online_publish_"
                + UUID.randomUUID().toString().replace("-", "")));
        mongo = new MongoTemplate(factory);
        MongoTransactionManager manager = new MongoTransactionManager(factory);
        transactions = new TransactionTemplate(manager);
        clock = new MutableClock(Instant.now().plusSeconds(5));
        publisherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        issuerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        author = actor("author", Set.of(ModelPolicyAuthority.CREATE, ModelPolicyAuthority.READ,
                ModelPolicyAuthority.SUBMIT));
        approver = actor("approver", Set.of(ModelPolicyAuthority.READ, ModelPolicyAuthority.APPROVE));
        MongoPolicyGovernanceStore governanceStore = new MongoPolicyGovernanceStore(mongo, manager);
        governance = new PolicyGovernanceService(governanceStore,
                new ModelPromotionPolicyValidator(mapper), mapper,
                "policy-publisher", "publisher-key", "policy-issuer", "issuer-key");
        webAuthn = new PolicyWebAuthnService(mongo, manager,
                new PolicyWebAuthnCredentialRepository(mongo), governance,
                governanceStore, mapper, new MockEnvironment(),
                "localhost", ORIGIN);
        Instant from = clock.instant().minusSeconds(86400);
        Instant to = clock.instant().plusSeconds(86400 * 365);
        actors = OnlinePolicyActorTrustRegistry.fromConfiguration(new OnlinePolicyActorTrustRegistry.Configuration(
                OnlinePolicyActorTrustRegistry.VERSION, 1, List.of(
                trustedActor(author, OnlinePolicyActorTrustRegistry.Role.POLICY_AUTHOR, from, to),
                trustedActor(approver, OnlinePolicyActorTrustRegistry.Role.POLICY_APPROVER, from, to))), 1);
        approvalTrust = InternalPolicyApprovalTrustRegistry.fromConfiguration(
                new InternalPolicyApprovalTrustRegistry.Configuration(InternalPolicyApprovalTrustRegistry.VERSION,
                        1, List.of(trustedKey("policy-publisher", "publisher-key", publisherKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER, from, to),
                                trustedKey("policy-issuer", "issuer-key", issuerKey,
                                        InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER, from, to))), 1);
        byte[] issuerDer = issuerKey.getPublic().getEncoded();
        var issuers = ModelPromotionTrustedIssuerRegistry.fromConfiguration(
                new ModelPromotionTrustedIssuerRegistry.Configuration(ModelPromotionTrustedIssuerRegistry.VERSION,
                        1, List.of(new ModelPromotionTrustedIssuerRegistry.ConfiguredKey("policy-issuer",
                                "issuer-key", Base64.getEncoder().encodeToString(issuerDer), sha(issuerDer),
                                List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                                List.of(ModelGovernanceEnvironment.TEST),
                                List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING), from.toString(),
                                to.toString(), ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE))), 1);
        issuerVerifier = new Ed25519ModelPromotionPolicyAuthorityVerifier(mapper, issuers, clock);
    }

    @AfterEach void tearDown() throws Exception {
        if (mongo != null) mongo.getDb().drop();
        if (factory != null) factory.destroy();
    }

    @Test void genuineWebAuthnApprovalPublishesVerifiedPolicyAndOriginalStaysImmutable() throws Exception {
        PolicyPublicationJobDocument job = approvedJob();
        assertThat(worker("worker-a", stage -> { }, issuerKey.getPrivate()).runOnce()).isTrue();
        PolicyPublicationJobDocument published = mongo.findById(job.id(), PolicyPublicationJobDocument.class);
        assertThat(published.state()).isEqualTo(PolicyPublicationJobDocument.State.PUBLISHED);
        assertThat(mongo.findById(job.proposalId(), PolicyProposalDocument.class).status())
                .isEqualTo(PolicyGovernanceState.PUBLISHED);
        String key = ModelPromotionPolicyDocument.policyKey(job.draft().governanceScope(),
                job.draft().intendedUse(), job.draft().policyId(), job.draft().revision());
        ModelPromotionPolicyDocument policy = mongo.findById(key, ModelPromotionPolicyDocument.class);
        assertThat(issuerVerifier.verify(policy.policy()).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        InternalPolicyApprovalEvidenceDocument proof = mongo.findById(key,
                InternalPolicyApprovalEvidenceDocument.class);
        assertThat(proof.onlineApprovalProof().publicationRequestId()).isEqualTo(job.id());
        assertThat(proof.onlineApprovalProof().approval().evidence().acceptedAt()).isEqualTo(job.decidedAt());
        var principal = new AnalystPrincipal("author", Set.of(), Set.of(ModelPolicyAuthority.READ),
                author.issuer() + "#" + author.subject());
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority(ModelPolicyAuthority.READ)));
        var status = new PolicyPublicationStatusController(governance, mongo, fullTrust())
                .publication(auth, job.proposalId());
        assertThat(status.state()).isEqualTo("PUBLISHED");
        assertThat(status.verificationStatus()).isEqualTo("FULLY_AUTHORIZED");
        assertThat(status.signatureStatus()).isEqualTo("VERIFIED");
        assertThat(status.approvalEvidenceStatus()).isEqualTo("APPROVAL_EVIDENCE_VERIFIED");
        assertThat(status.policySha256()).isEqualTo(policy.policy().canonicalSha256());
        assertThat(worker("worker-b", stage -> { }, issuerKey.getPrivate()).runOnce()).isFalse();
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isEqualTo(1);
        assertThat(mongo.findById(key, ModelPromotionPolicyDocument.class)).isEqualTo(policy);
    }

    @Test void crashesBeforeSigningAfterSigningAndAfterCommitRecoverThroughLease() throws Exception {
        for (PolicyPublicationWorker.Stage stage : PolicyPublicationWorker.Stage.values()) {
            PolicyPublicationJobDocument job = approvedJob();
            PolicyPublicationWorker crashing = worker("crashing-" + stage,
                    checkpoint -> { if (checkpoint == stage) throw new AssertionError("simulated process crash"); },
                    issuerKey.getPrivate());
            assertThatThrownBy(crashing::runOnce).isInstanceOf(AssertionError.class);
            assertThat(mongo.findById(job.id(), PolicyPublicationJobDocument.class).state())
                    .isEqualTo(PolicyPublicationJobDocument.State.CLAIMED);
            clock.set(clock.instant().plusSeconds(121));
            assertThat(worker("restarted-" + stage, ignored -> { }, issuerKey.getPrivate()).runOnce()).isTrue();
            assertThat(mongo.findById(job.id(), PolicyPublicationJobDocument.class).state())
                    .isEqualTo(PolicyPublicationJobDocument.State.PUBLISHED);
            mongo.getDb().drop();
        }
    }

    @Test void secondWorkerCannotClaimLiveLeaseButCanTakeOverExpiredLease() throws Exception {
        PolicyPublicationJobDocument job = approvedJob();
        PolicyPublicationWorker first = worker("worker-first", checkpoint -> {
            if (checkpoint == PolicyPublicationWorker.Stage.BEFORE_SIGN) {
                assertThat(worker("worker-second", ignored -> { }, issuerKey.getPrivate()).runOnce()).isFalse();
                throw new AssertionError("worker stopped after claim");
            }
        }, issuerKey.getPrivate());
        assertThatThrownBy(first::runOnce).isInstanceOf(AssertionError.class);
        assertThat(mongo.findById(job.id(), PolicyPublicationJobDocument.class).state())
                .isEqualTo(PolicyPublicationJobDocument.State.CLAIMED);
        clock.set(clock.instant().plusSeconds(121));
        assertThat(worker("worker-second", ignored -> { }, issuerKey.getPrivate()).runOnce()).isTrue();
        assertThat(mongo.findById(job.id(), PolicyPublicationJobDocument.class).state())
                .isEqualTo(PolicyPublicationJobDocument.State.PUBLISHED);
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isEqualTo(1);
    }

    @Test void expiredFinalAttemptReconcilesCommitWithoutSigningAgain() throws Exception {
        PolicyPublicationJobDocument job = approvedJob();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(job.id())),
                new Update().set("attempts", 4), PolicyPublicationJobDocument.class);
        PolicyPublicationWorker finalAttempt = worker("worker-final", checkpoint -> {
            if (checkpoint == PolicyPublicationWorker.Stage.AFTER_COMMIT) {
                throw new AssertionError("lost publication acknowledgement");
            }
        }, issuerKey.getPrivate());
        assertThatThrownBy(finalAttempt::runOnce).isInstanceOf(AssertionError.class);
        clock.set(clock.instant().plusSeconds(121));
        assertThat(worker("worker-reconcile", ignored -> {
            throw new AssertionError("reconciliation must not sign again");
        }, issuerKey.getPrivate()).runOnce()).isTrue();
        assertThat(mongo.findById(job.id(), PolicyPublicationJobDocument.class).state())
                .isEqualTo(PolicyPublicationJobDocument.State.PUBLISHED);
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isEqualTo(1);
    }

    @Test void expiredFinalAttemptWithoutCommitBlocksRatherThanSigningAgain() throws Exception {
        PolicyPublicationJobDocument job = approvedJob();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(job.id())),
                new Update().set("attempts", 4), PolicyPublicationJobDocument.class);
        assertThatThrownBy(worker("worker-final", checkpoint -> {
            if (checkpoint == PolicyPublicationWorker.Stage.BEFORE_SIGN) {
                throw new AssertionError("crash before signing");
            }
        }, issuerKey.getPrivate())::runOnce).isInstanceOf(AssertionError.class);
        clock.set(clock.instant().plusSeconds(121));
        assertThat(worker("worker-reconcile", ignored -> {
            throw new AssertionError("reconciliation must not sign");
        }, issuerKey.getPrivate()).runOnce()).isTrue();
        assertThat(mongo.findById(job.id(), PolicyPublicationJobDocument.class).state())
                .isEqualTo(PolicyPublicationJobDocument.State.BLOCKED);
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isZero();
    }

    @Test void revokedApproverOrWrongIssuerKeyNeverPublishes() throws Exception {
        PolicyPublicationJobDocument revoked = approvedJob();
        String approvalCredential = mongo.findById(revoked.approvalEvidenceReference().substring(9),
                com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnEvidence.class).credentialId();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(approvalCredential)),
                new Update().set("revoked", true), PolicyWebAuthnCredential.class);
        worker("worker-revoked", ignored -> { }, issuerKey.getPrivate()).runOnce();
        assertThat(mongo.findById(revoked.id(), PolicyPublicationJobDocument.class).state())
                .isEqualTo(PolicyPublicationJobDocument.State.BLOCKED);
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isZero();

        PolicyPublicationJobDocument wrong = approvedJob();
        PrivateKey wrongIssuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        worker("worker-wrong-key", ignored -> { }, wrongIssuer).runOnce();
        assertThat(mongo.findById(wrong.id(), PolicyPublicationJobDocument.class).state())
                .isEqualTo(PolicyPublicationJobDocument.State.BLOCKED);
        assertThat(mongo.count(new Query(), ModelPromotionPolicyDocument.class)).isZero();
    }

    private PolicyPublicationJobDocument approvedJob() throws Exception {
        Authenticator authorKey = credential(author);
        Authenticator approverKey = credential(approver);
        List<ModelPromotionPolicy.CriterionRule> rules = Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(Collectors.toMap(k -> k,
                                k -> BigDecimal.ONE)), 86400, "VERIFIED", criterion.evidenceSource(),
                        criterion.metricSemantics(), criterion.populationDefinition(),
                        criterion.denominatorDefinition(), null)).toList();
        Instant effective = clock.instant().plusSeconds(86400 * 30)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var input = new DraftInput("policy-" + UUID.randomUUID(), 1,
                new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, ModelGovernanceEnvironment.TEST),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, approver.id(), "review-1",
                effective.toString(), effective.plusSeconds(86400 * 30).toString(), rules);
        PolicyProposalDocument draft = governance.create(author, new DraftCommand("create-" + UUID.randomUUID(), input));
        var submit = webAuthn.startAction(author, draft.id(),
                new PolicyWebAuthnService.ActionIntent("submit-" + UUID.randomUUID(), 0, null), "submit");
        var submitted = webAuthn.finishAction(author, draft.id(),
                new PolicyWebAuthnService.FinishAction(mongo.findById(submit.challengeId(),
                        PolicyWebAuthnChallenge.class).operationId(), submit.challengeId(),
                        authorKey.assertion(submit.challengeId(), 1)), "submit");
        var approve = webAuthn.startAction(approver, draft.id(),
                new PolicyWebAuthnService.ActionIntent("approve-" + UUID.randomUUID(), submitted.version(),
                        ReviewDecision.APPROVE, "Independent approval"), "review");
        var approved = webAuthn.finishAction(approver, draft.id(),
                new PolicyWebAuthnService.FinishAction(mongo.findById(approve.challengeId(),
                        PolicyWebAuthnChallenge.class).operationId(), approve.challengeId(),
                        approverKey.assertion(approve.challengeId(), 1)), "review");
        PolicyGovernanceActor requester = actor("requester", Set.of(ModelPolicyAuthority.PUBLISH_REQUEST));
        var pending = governance.requestPublication(requester, draft.id(),
                new TransitionCommand("publish-" + UUID.randomUUID(), approved.version()));
        return mongo.findById(pending.publicationRequestId(), PolicyPublicationJobDocument.class);
    }

    private PolicyPublicationWorker worker(String workerId, PolicyPublicationWorker.Checkpoints checkpoints,
            PrivateKey signingKey) {
        var store = new MongoModelPromotionPolicyPublicationStore(mongo, transactions);
        var publisher = new ModelPromotionPolicyPublisher(mapper, new ModelPromotionPolicyValidator(mapper),
                issuerVerifier, store, clock);
        var approval = new OnlinePolicyApprovalVerifier(mongo, mapper, clock, "localhost", ORIGIN,
                actors, approvalTrust);
        return new PolicyPublicationWorker(mongo, transactions, publisher, approval, issuerVerifier,
                signingKey, publisherKey.getPrivate(), approvalTrust, clock, mapper, workerId, checkpoints);
    }

    private ModelPromotionPolicyFullTrustVerifier fullTrust() {
        var online = new OnlinePolicyApprovalVerifier(mongo, mapper, clock, "localhost", ORIGIN,
                actors, approvalTrust);
        return new ModelPromotionPolicyFullTrustVerifier(issuerVerifier,
                new MongoModelPromotionPolicyApprovalEvidenceVerifier(mongo, mapper, clock,
                        approvalTrust, online));
    }

    private Authenticator credential(PolicyGovernanceActor actor) throws Exception {
        Authenticator key = new Authenticator(actor);
        ECPublicKey publicKey = (ECPublicKey) key.pair.getPublic();
        byte[] cose = CBORObject.NewMap().Add(1, 2).Add(3, -7).Add(-1, 1)
                .Add(-2, fixed(publicKey.getW().getAffineX()))
                .Add(-3, fixed(publicKey.getW().getAffineY())).EncodeToBytes();
        String userHandle = encode(HexFormat.of().parseHex(actor.id().substring(5)));
        mongo.insert(new PolicyWebAuthnCredential(key.id, actor.id(), actor.issuer(), actor.subject(),
                userHandle, encode(cose), 0, false, Instant.now(), Instant.now()));
        return key;
    }

    private OnlinePolicyActorTrustRegistry.Actor trustedActor(PolicyGovernanceActor actor,
            OnlinePolicyActorTrustRegistry.Role role, Instant from, Instant to) {
        return new OnlinePolicyActorTrustRegistry.Actor(actor.id(), actor.issuer(), actor.subject(), List.of(role),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION), List.of(ModelGovernanceEnvironment.TEST),
                List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING), from.toString(), to.toString(),
                OnlinePolicyActorTrustRegistry.Status.ACTIVE);
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey trustedKey(String identity, String keyId,
            KeyPair pair, InternalPolicyApprovalTrustRegistry.Role role, Instant from, Instant to) throws Exception {
        byte[] der = pair.getPublic().getEncoded();
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(identity, keyId,
                Base64.getEncoder().encodeToString(der), sha(der), List.of(role),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION), List.of(ModelGovernanceEnvironment.TEST),
                List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING), from.toString(), to.toString(),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
    }

    private PolicyGovernanceActor actor(String subject, Set<String> authorities) throws Exception {
        String issuer = "https://issuer.example";
        return new PolicyGovernanceActor("oidc:" + sha((issuer + "#" + subject).getBytes(StandardCharsets.UTF_8)),
                authorities, issuer, subject);
    }

    private String sha(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private byte[] fixed(java.math.BigInteger value) {
        byte[] encoded = value.toByteArray();
        byte[] out = new byte[32];
        System.arraycopy(encoded, Math.max(0, encoded.length - 32), out,
                Math.max(0, 32 - encoded.length), Math.min(32, encoded.length));
        return out;
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private final class Authenticator {
        private final PolicyGovernanceActor actor;
        private final KeyPair pair;
        private final String id = encode(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

        private Authenticator(PolicyGovernanceActor actor) throws Exception {
            this.actor = actor;
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
        }

        private String assertion(String challengeId, int counter) throws Exception {
            String challenge = AssertionRequest.fromJson(mongo.findById(challengeId,
                    PolicyWebAuthnChallenge.class).requestJson())
                    .getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url();
            byte[] clientData = json.writeValueAsBytes(Map.of("type", "webauthn.get", "challenge", challenge,
                    "origin", ORIGIN, "crossOrigin", false));
            ByteArrayOutputStream authenticator = new ByteArrayOutputStream();
            authenticator.write(MessageDigest.getInstance("SHA-256")
                    .digest("localhost".getBytes(StandardCharsets.UTF_8)));
            authenticator.write(0x05);
            authenticator.write(ByteBuffer.allocate(4).putInt(counter).array());
            byte[] authData = authenticator.toByteArray();
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            signature.update(authData);
            signature.update(MessageDigest.getInstance("SHA-256").digest(clientData));
            return json.writeValueAsString(Map.of("id", id, "rawId", id, "type", "public-key",
                    "response", Map.of("clientDataJSON", encode(clientData),
                            "authenticatorData", encode(authData), "signature", encode(signature.sign()),
                            "userHandle", encode(HexFormat.of().parseHex(actor.id().substring(5)))),
                    "clientExtensionResults", Map.of()));
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time;
        private MutableClock(Instant start) { time = new AtomicReference<>(start); }
        private void set(Instant next) { time.set(next); }
        @Override public Instant instant() { return time.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
