package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
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
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
    private PolicyCredentialGrantReviewService grantReviews;
    private PolicyCredentialGrantAuthorizationVerifier grantAuthorization;
    private Path bootstrapPath;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    @TempDir Path trustTempDir;

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
        bootstrapPath = trustTempDir.resolve("first-install.json");
        var bootstrap = new PolicyCredentialBootstrapAuthority(mapper, bootstrapPath.toString());
        grantAuthorization = new PolicyCredentialGrantAuthorizationVerifier(mongo, mapper, bootstrap);
        grants = new PolicyCredentialEnrollmentGrantService(mongo, manager, governanceStore,
                grantAuthorization);
        grants.ensureIndexes();
        Environment environment = new MockEnvironment();
        webAuthn = new PolicyWebAuthnService(mongo, manager, credentials, governance, governanceStore,
                grants, mapper, environment, "localhost", ORIGIN);
        webAuthn.ensureIndexes();
        grantReviews = new PolicyCredentialGrantReviewService(mongo, manager, governanceStore,
                webAuthn, grants, mapper, bootstrap, credentials);
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
        assertThatThrownBy(() -> grantReviews.start(credentialAdmin("security-operator-a"), id,
                new PolicyCredentialGrantReviewService.ReviewIntent("self-review",
                        PolicyCredentialGrantReviewService.Decision.APPROVE)))
                .isInstanceOf(AccessDeniedException.class);
        String token = fixtureApproveAndClaim(author(), id);
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
        grants.clearExpiredGrantDigests();
        assertThat(mongo.findById(id, PolicyCredentialEnrollmentGrantDocument.class).secretSha256()).isNull();
    }

    @Test
    void signedGrantDecisionBindsExactRequestAndOnlyTargetCanClaimOnce() throws Exception {
        var reviewer = actor("security-operator-b", Set.of(
                ModelPolicyAuthority.CREDENTIAL_ADMIN, ModelPolicyAuthority.CREDENTIAL_ENROLL));
        Authenticator reviewerKey = register(reviewer);
        String id = "signed-grant-" + UUID.randomUUID();
        grants.request(credentialAdmin("security-operator-a"),
                new PolicyCredentialEnrollmentGrantService.GrantRequest(id, author().issuer(),
                        author().subject(), PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST,
                        "case-strong-review"));
        var intent = new PolicyCredentialGrantReviewService.ReviewIntent("review-" + UUID.randomUUID(),
                PolicyCredentialGrantReviewService.Decision.APPROVE);
        var challenge = grantReviews.start(reviewer, id, intent);
        assertThatThrownBy(() -> grantReviews.finish(reviewer, id,
                new PolicyCredentialGrantReviewService.FinishReview(
                        intent, challenge.challengeId(), "{}")))
                .isInstanceOf(RuntimeException.class);
        String assertion = reviewerKey.assertion(challenge.challengeId(), ORIGIN, "localhost", true, 1);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("purpose", PolicyCredentialEnrollmentGrantDocument.Purpose.ADDITIONAL),
                PolicyCredentialEnrollmentGrantDocument.class);
        var command = new PolicyCredentialGrantReviewService.FinishReview(
                intent, challenge.challengeId(), assertion);
        assertThatThrownBy(() -> grantReviews.finish(reviewer, id, command))
                .isInstanceOf(RuntimeException.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("purpose", PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST),
                PolicyCredentialEnrollmentGrantDocument.class);
        mongo.insert(new PolicyCredentialSecurityEventDocument("grant-review:" + id,
                "collision", author().id(), author().issuer(), author().subject(),
                reviewer.id(), reviewer.id(), "case-strong-review", null, clock.instant()));
        assertThatThrownBy(() -> grantReviews.finish(reviewer, id, command))
                .isInstanceOf(RuntimeException.class);
        assertThat(mongo.findById(id, PolicyCredentialEnrollmentGrantDocument.class).approvedAt()).isNull();
        mongo.remove(Query.query(Criteria.where("_id").is("grant-review:" + id)),
                PolicyCredentialSecurityEventDocument.class);
        grantReviews.finish(reviewer, id, command);
        var evidence = grantReviews.verifyHistorical(actor("auditor",
                Set.of(ModelPolicyAuthority.AUDIT_READ)), id);
        assertThat(evidence.decision()).isEqualTo(PolicyCredentialGrantReviewService.Decision.APPROVE);
        assertThatThrownBy(() -> grantReviews.finish(reviewer, id, command))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> grants.claim(approver(), id)).isInstanceOf(AccessDeniedException.class);
        var approvedGrant = mongo.findById(id, PolicyCredentialEnrollmentGrantDocument.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("expiresAt", approvedGrant.expiresAt().plusSeconds(1)),
                PolicyCredentialEnrollmentGrantDocument.class);
        assertThatThrownBy(() -> grants.claim(author(), id)).isInstanceOf(SecurityException.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("expiresAt", approvedGrant.expiresAt()),
                PolicyCredentialEnrollmentGrantDocument.class);
        var restartedManager = new MongoTransactionManager(factory);
        var restartedStore = new MongoPolicyGovernanceStore(mongo,
                new TransactionTemplate(restartedManager), clock);
        var restartedGrants = new PolicyCredentialEnrollmentGrantService(mongo, restartedManager,
                restartedStore, new PolicyCredentialGrantAuthorizationVerifier(mongo,
                        new ObjectMapper(), new PolicyCredentialBootstrapAuthority(
                                new ObjectMapper(), bootstrapPath.toString())));
        String token = restartedGrants.claim(author(), id).token();
        assertThatThrownBy(() -> grants.claim(author(), id)).isInstanceOf(AccessDeniedException.class);
        assertThat(grants.validate(author(), token,
                PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST).id()).isEqualTo(id);
    }

    @Test
    void targetClaimIsAtomicAndLostResponseCannotReissueCapability() throws Exception {
        var reviewer = actor("security-operator-b", Set.of(
                ModelPolicyAuthority.CREDENTIAL_ADMIN, ModelPolicyAuthority.CREDENTIAL_ENROLL));
        Authenticator reviewerKey = register(reviewer);
        String id = "claim-race-" + UUID.randomUUID();
        grants.request(credentialAdmin("security-operator-a"),
                new PolicyCredentialEnrollmentGrantService.GrantRequest(id, author().issuer(),
                        author().subject(), PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST,
                        "case-claim-race"));
        var intent = new PolicyCredentialGrantReviewService.ReviewIntent("review-" + UUID.randomUUID(),
                PolicyCredentialGrantReviewService.Decision.APPROVE);
        var challenge = grantReviews.start(reviewer, id, intent);
        grantReviews.finish(reviewer, id, new PolicyCredentialGrantReviewService.FinishReview(
                intent, challenge.challengeId(), reviewerKey.assertion(
                        challenge.challengeId(), ORIGIN, "localhost", true, 1)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> pool.submit(() -> {
                ready.countDown(); go.await();
                try { return grants.claim(author(), id).token(); }
                catch (RuntimeException rejected) { return null; }
            })).toList();
            ready.await(); go.countDown();
            assertThat(calls.stream().map(call -> {
                try { return call.get(); } catch (Exception failure) { throw new RuntimeException(failure); }
            }).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThatThrownBy(() -> grants.claim(author(), id)).isInstanceOf(AccessDeniedException.class);
        assertThat(mongo.count(Query.query(Criteria.where("_id").is("grant-claim:" + id)),
                PolicyCredentialSecurityEventDocument.class)).isEqualTo(1);
    }

    @Test
    void approvedFlagWithoutSignedOrBootstrapEvidenceCannotIssueCapability() {
        String id = "unproven-grant-" + UUID.randomUUID();
        grants.request(credentialAdmin("security-operator-a"),
                new PolicyCredentialEnrollmentGrantService.GrantRequest(id, author().issuer(),
                        author().subject(), PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST,
                        "case-no-proof"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("approvedBy", credentialAdmin("security-operator-b").id())
                        .set("approvedAt", clock.instant())
                        .set("expiresAt", clock.instant().plusSeconds(600)),
                PolicyCredentialEnrollmentGrantDocument.class);
        assertThatThrownBy(() -> grants.claim(author(), id)).isInstanceOf(SecurityException.class);
        assertThat(mongo.findById(id, PolicyCredentialEnrollmentGrantDocument.class).claimedAt()).isNull();
    }

    @Test
    void missingBootstrapAuthorityFailsClosedAndExactFirstInstallIsSingleUse() throws Exception {
        String id = "bootstrap-" + UUID.randomUUID();
        var requester = credentialAdmin("security-operator-a");
        var reviewer = credentialAdmin("security-operator-b");
        grants.request(requester, new PolicyCredentialEnrollmentGrantService.GrantRequest(
                id, author().issuer(), author().subject(),
                PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST, "case-bootstrap"));
        assertThatThrownBy(() -> grantReviews.bootstrapApprove(reviewer, id))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> grantReviews.start(reviewer, id,
                new PolicyCredentialGrantReviewService.ReviewIntent("oidc-only",
                        PolicyCredentialGrantReviewService.Decision.APPROVE)))
                .isInstanceOf(AccessDeniedException.class);
        var request = mongo.findById(id, PolicyCredentialEnrollmentGrantDocument.class);
        Files.writeString(bootstrapPath, new ObjectMapper().writeValueAsString(
                new PolicyCredentialBootstrapAuthority.Authorization(
                        PolicyCredentialBootstrapAuthority.Authorization.VERSION, "bootstrap-authority-1",
                        id, request.targetIssuer(), request.targetSubject(), request.requestedBy(),
                        reviewer.id(), request.caseReference(), request.requestExpiresAt().toString(),
                        request.requestExpiresAt().toString(), "installation-authority",
                        "independent-installation-authorizer")));
        grantReviews.bootstrapApprove(reviewer, id);
        assertThatThrownBy(() -> grantReviews.bootstrapApprove(reviewer, id))
                .isInstanceOf(RuntimeException.class);
        assertThat(grants.claim(author(), id).requestId()).isEqualTo(id);
    }

    @Test
    void signedRejectionAndExpiredApprovalNeverIssueTargetCapability() throws Exception {
        var reviewer = actor("security-operator-b", Set.of(
                ModelPolicyAuthority.CREDENTIAL_ADMIN, ModelPolicyAuthority.CREDENTIAL_ENROLL));
        Authenticator reviewerKey = register(reviewer);
        String rejectedId = "rejected-grant-" + UUID.randomUUID();
        grants.request(credentialAdmin("security-operator-a"),
                new PolicyCredentialEnrollmentGrantService.GrantRequest(rejectedId,
                        author().issuer(), author().subject(),
                        PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST, "case-rejected"));
        var reject = new PolicyCredentialGrantReviewService.ReviewIntent("reject-" + UUID.randomUUID(),
                PolicyCredentialGrantReviewService.Decision.REJECT);
        var rejectChallenge = grantReviews.start(reviewer, rejectedId, reject);
        grantReviews.finish(reviewer, rejectedId, new PolicyCredentialGrantReviewService.FinishReview(
                reject, rejectChallenge.challengeId(), reviewerKey.assertion(
                        rejectChallenge.challengeId(), ORIGIN, "localhost", true, 1)));
        assertThat(grantReviews.verifyHistorical(actor("auditor",
                Set.of(ModelPolicyAuthority.AUDIT_READ)), rejectedId).decision())
                .isEqualTo(PolicyCredentialGrantReviewService.Decision.REJECT);
        assertThatThrownBy(() -> grants.claim(author(), rejectedId)).isInstanceOf(AccessDeniedException.class);

        String approvedId = "expired-grant-" + UUID.randomUUID();
        grants.request(credentialAdmin("security-operator-a"),
                new PolicyCredentialEnrollmentGrantService.GrantRequest(approvedId,
                        author().issuer(), author().subject(),
                        PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST, "case-expired"));
        var approve = new PolicyCredentialGrantReviewService.ReviewIntent("approve-" + UUID.randomUUID(),
                PolicyCredentialGrantReviewService.Decision.APPROVE);
        var approveChallenge = grantReviews.start(reviewer, approvedId, approve);
        grantReviews.finish(reviewer, approvedId, new PolicyCredentialGrantReviewService.FinishReview(
                approve, approveChallenge.challengeId(), reviewerKey.assertion(
                        approveChallenge.challengeId(), ORIGIN, "localhost", true, 2)));
        clock.set(clock.instant().plusSeconds(601));
        assertThatThrownBy(() -> grants.claim(author(), approvedId)).isInstanceOf(AccessDeniedException.class);
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
        assertThat(mongo.findById(grantId, PolicyCredentialEnrollmentGrantDocument.class).secretSha256()).isNull();
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
        var existing = actor("existing-approver", Set.of());
        Path actorRegistry = trustTempDir.resolve("online-actor-trust.json");
        Files.writeString(actorRegistry, """
                {"version":"model-policy-online-actor-trust-v1","generation":1,"actors":[
                {"actorId":"%s","issuer":"%s","subject":"%s",
                 "roles":["POLICY_APPROVER"],"institutionalScopes":["CURRENT_INSTITUTION"],
                 "environments":["TEST"],"intendedUses":["FRAUD_RISK_SCORING"],
                 "validFrom":"2026-01-01T00:00:00Z","validUntil":"2028-01-01T00:00:00Z",
                 "status":"ACTIVE"}]}
                """.formatted(existing.id(), existing.issuer(), existing.subject()));
        var registries = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService(
                new ObjectMapper(), actorRegistry.toString(), "", "", 1, 1, 1);
        var changes = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService(
                mongo, new TransactionTemplate(new MongoTransactionManager(factory)), webAuthn,
                registries, new ObjectMapper(), clock,
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustFleetConsistency.membership(
                        "rollout-test", "instance-a", "instance-b"));
        var target = actor("new-approver", Set.of());
        var intent = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Intent(
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Action.GRANT,
                target.id(), null, null, null,
                List.of("POLICY_APPROVER"), ModelGovernanceEnvironment.PRODUCTION,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 2, null, "case-trust-1",
                target.issuer(), target.subject(), "New approver",
                "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", null, null);
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
        var auditReader = actor("trust-auditor", Set.of(ModelPolicyAuthority.AUDIT_READ,
                ModelPolicyAuthority.PRODUCTION));
        var reopenedChanges = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService(
                mongo, new TransactionTemplate(new MongoTransactionManager(factory)), webAuthn,
                registries, new ObjectMapper(), clock);
        assertThatThrownBy(() -> reopenedChanges.verifyHistoricalChange(
                actor("unscoped-auditor", Set.of(ModelPolicyAuthority.AUDIT_READ)), requested.id()))
                .isInstanceOf(AccessDeniedException.class);
        reopenedChanges.verifyHistoricalChange(auditReader, requested.id());
        assertThat(approved.status()).isEqualTo("PENDING_OPERATOR_ACTION");
        String approvedDigest = approved.candidateSha256();
        assertThat(approvedDigest).matches("[0-9a-f]{64}");
        var installation = reopenedChanges.installation(auditReader, approved.id());
        var installer = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustCandidateInstallationVerifier(
                new ObjectMapper(), clock);
        assertThat(installer.verify(installation, actorRegistry, 1).sha256()).isEqualTo(approvedDigest);
        Path staged = trustTempDir.resolve("approved-online-actors-2.json");
        assertThat(installer.stage(installation, actorRegistry, staged, 1).sha256())
                .isEqualTo(approvedDigest);
        assertThat(Files.readString(staged)).isEqualTo(installation.candidate().canonicalJson());
        assertThatThrownBy(() -> installer.stage(installation, actorRegistry, staged, 1))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        var candidate = installation.candidate();
        var substituted = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustCandidateDocument(
                candidate.id(), candidate.registry(), candidate.action(), candidate.baseGeneration(),
                candidate.baseSha256(), candidate.generation(), "{}\n", candidate.sha256(),
                candidate.semanticDiffJson(), candidate.reviewSummary(), candidate.affectsProduction());
        assertThatThrownBy(() -> installer.verify(
                new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.InstallationBundle(
                        installation.request(), substituted, installation.requestProof(), installation.reviewProof(),
                        installation.requestAudit(), installation.reviewAudit()), actorRegistry, 1))
                .isInstanceOf(SecurityException.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(approved.id())),
                new Update().set("canonicalJson", "{}\n"),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustCandidateDocument.class);
        assertThatThrownBy(() -> reopenedChanges.installation(auditReader, approved.id()))
                .isInstanceOf(SecurityException.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(approved.id())),
                new Update().set("canonicalJson", candidate.canonicalJson()),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustCandidateDocument.class);
        var rejectedRequestOptions = changes.startRequest(requester, "trust-op-rejected", intent);
        var rejectedRequest = changes.finishRequest(requester, "trust-op-rejected", intent,
                rejectedRequestOptions.challengeId(), requesterKey.assertion(
                        rejectedRequestOptions.challengeId(), ORIGIN, "localhost", true, 2));
        var rejectOptions = changes.startReview(reviewer, rejectedRequest.id(), "trust-review-rejected",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.REJECT);
        var rejected = changes.finishReview(reviewer, rejectedRequest.id(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.REJECT,
                rejectOptions.challengeId(), reviewerKey.assertion(
                        rejectOptions.challengeId(), ORIGIN, "localhost", true, 2));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> reopenedChanges.installation(auditReader, rejected.id()))
                .isInstanceOf(RuntimeException.class);
        reopenedChanges.verifyHistoricalChange(auditReader, rejected.id());
        var competingOptions = changes.startRequest(requester, "trust-op-competing", intent);
        var competing = changes.finishRequest(requester, "trust-op-competing", intent,
                competingOptions.challengeId(), requesterKey.assertion(
                        competingOptions.challengeId(), ORIGIN, "localhost", true, 3));
        var competingReview = changes.startReview(reviewer, competing.id(), "trust-review-competing",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE);
        assertThatThrownBy(() -> changes.finishReview(reviewer, competing.id(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE,
                competingReview.challengeId(), reviewerKey.assertion(
                        competingReview.challengeId(), ORIGIN, "localhost", true, 3)))
                .isInstanceOf(RuntimeException.class);
        assertThat(mongo.findById(competing.id(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeDocument.class).status())
                .isEqualTo("PENDING_REVIEW");
        assertThat(changes.deployment(reviewer, approved.id()).rolloutState())
                .isEqualTo("APPROVED_PENDING_OPERATOR_ACTION");
        mongo.save(new com.frauddetection.alert.governance.modelpromotion.PolicyTrustInstanceObservation(
                "ALERT_VERIFIER:instance-a:epoch-a:ONLINE_ACTORS", "ALERT_VERIFIER", "instance-a",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                2, approvedDigest, clock.instant(), "rollout-test", "epoch-a", clock.instant().minusSeconds(30)));
        assertThat(changes.deployment(reviewer, approved.id()).rolloutState())
                .isEqualTo("OBSERVED_PARTIAL");
        mongo.save(new com.frauddetection.alert.governance.modelpromotion.PolicyTrustInstanceObservation(
                "PUBLISHER:instance-b:epoch-b:ONLINE_ACTORS", "PUBLISHER", "instance-b",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                1, registries.requireReady(intent.registry()).digestSha256(), clock.instant(),
                "rollout-test", "epoch-b", clock.instant().minusSeconds(30)));
        assertThat(changes.deployment(reviewer, approved.id()).rolloutState())
                .isEqualTo("OBSERVED_PARTIAL");
        mongo.save(new com.frauddetection.alert.governance.modelpromotion.PolicyTrustInstanceObservation(
                "PUBLISHER:instance-b:epoch-b:ONLINE_ACTORS", "PUBLISHER", "instance-b",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                2, approvedDigest, clock.instant(), "rollout-test", "epoch-b", clock.instant().minusSeconds(30)));
        var oldApiView = changes.deployment(reviewer, approved.id());
        assertThat(oldApiView.rolloutState()).isEqualTo("DEPLOYMENT_FAILED");
        assertThat(oldApiView.consistentObservedGeneration()).isNull();
        var installedRegistries = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService(
                new ObjectMapper(), staged.toString(), "", "", 1, 1, 1);
        var installedChanges = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService(
                mongo, new TransactionTemplate(new MongoTransactionManager(factory)), webAuthn,
                installedRegistries, new ObjectMapper(), clock,
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustFleetConsistency.membership(
                        "rollout-test", "instance-a", "instance-b"));
        var observed = installedChanges.deployment(reviewer, approved.id());
        assertThat(observed.rolloutState()).isEqualTo("OBSERVED_CONSISTENT");
        assertThat(observed.consistentObservedGeneration()).isEqualTo(2L);
        assertThat(observed.consistentObservedRegistrySha256()).isEqualTo(approvedDigest);
        assertThat(observed.observationSetTruncated()).isFalse();
        assertThat(mongo.count(new Query(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeAuditDocument.class))
                .isEqualTo(5);
        assertThat(mongo.count(new Query(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeDocument.class))
                .isEqualTo(3);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(requested.id())),
                new Update().set("intent.candidateRegistrySha256", "c".repeat(64)),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeDocument.class);
        assertThatThrownBy(() -> reopenedChanges.verifyHistoricalChange(auditReader, requested.id()))
                .isInstanceOf(SecurityException.class);

        var nextRegistries = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustRegistrySnapshotService(
                new ObjectMapper(), staged.toString(), "", "", 1, 1, 1);
        var nextChanges = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService(
                mongo, new TransactionTemplate(new MongoTransactionManager(factory)), webAuthn,
                nextRegistries, new ObjectMapper(), clock);
        var secondReviewer = actor("trust-reviewer-two", Set.of(ModelPolicyAuthority.CREDENTIAL_ENROLL,
                ModelPolicyAuthority.TRUST_APPROVE, ModelPolicyAuthority.PRODUCTION));
        Authenticator secondReviewerKey = register(secondReviewer);
        var revokeExisting = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Intent(
                intent.registry(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Action.REVOKE,
                existing.id(), null, null, null, List.of("POLICY_APPROVER"),
                ModelGovernanceEnvironment.TEST, ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                3, null, "case-trust-race-1");
        var revokeNew = new com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Intent(
                intent.registry(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Action.REVOKE,
                target.id(), null, null, null, List.of("POLICY_APPROVER"),
                ModelGovernanceEnvironment.PRODUCTION, ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                3, null, "case-trust-race-2");
        var firstRaceChallenge = nextChanges.startRequest(requester, "trust-race-request-1", revokeExisting);
        var firstRace = nextChanges.finishRequest(requester, "trust-race-request-1", revokeExisting,
                firstRaceChallenge.challengeId(), requesterKey.assertion(firstRaceChallenge.challengeId(),
                        ORIGIN, "localhost", true, 4));
        var secondRaceChallenge = nextChanges.startRequest(requester, "trust-race-request-2", revokeNew);
        var secondRace = nextChanges.finishRequest(requester, "trust-race-request-2", revokeNew,
                secondRaceChallenge.challengeId(), requesterKey.assertion(secondRaceChallenge.challengeId(),
                        ORIGIN, "localhost", true, 5));
        var firstReview = nextChanges.startReview(reviewer, firstRace.id(), "trust-race-review-1",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE);
        var secondReview = nextChanges.startReview(secondReviewer, secondRace.id(), "trust-race-review-2",
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE);
        String firstAssertion = reviewerKey.assertion(firstReview.challengeId(), ORIGIN, "localhost", true, 3);
        String secondAssertion = secondReviewerKey.assertion(secondReview.challengeId(),
                ORIGIN, "localhost", true, 1);
        CountDownLatch raceStart = new CountDownLatch(1);
        var raceWorkers = Executors.newFixedThreadPool(2);
        try {
            var one = raceWorkers.submit(() -> {
                raceStart.await();
                try {
                    nextChanges.finishReview(reviewer, firstRace.id(),
                            com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE,
                            firstReview.challengeId(), firstAssertion);
                    return true;
                } catch (RuntimeException conflict) { return false; }
            });
            var two = raceWorkers.submit(() -> {
                raceStart.await();
                try {
                    nextChanges.finishReview(secondReviewer, secondRace.id(),
                            com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeService.Decision.APPROVE,
                            secondReview.challengeId(), secondAssertion);
                    return true;
                } catch (RuntimeException conflict) { return false; }
            });
            raceStart.countDown();
            assertThat((one.get() ? 1 : 0) + (two.get() ? 1 : 0)).isEqualTo(1);
        } finally {
            raceWorkers.shutdownNow();
        }
        assertThat(mongo.count(new Query(),
                com.frauddetection.alert.governance.modelpromotion.PolicyTrustGenerationClaimDocument.class))
                .isEqualTo(2);
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
        var reopenedGrants = new PolicyCredentialEnrollmentGrantService(mongo, manager, store,
                grantAuthorization);
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
        for (String invalid : List.of(
                key.assertionWithChallenge("wrong-challenge", ORIGIN, "localhost", true, 1),
                key.assertionWithChallenge(challenge, "https://wrong.example", "localhost", true, 1),
                key.assertionWithChallenge(challenge, ORIGIN, "wrong.example", true, 1),
                key.assertionWithChallenge(challenge, ORIGIN, "localhost", false, 1))) {
            assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(
                    author(), "model-promotion-review", options.challengeId(), invalid, contract)))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(mongo.findById(options.challengeId(), GovernanceWebAuthnBoundChallenge.class).used()).isFalse();
        var proof = transaction.execute(ignored -> webAuthn.acceptBoundAssertion(author(),
                "model-promotion-review", options.challengeId(), assertion, contract));
        assertThat(proof.credentialId()).isEqualTo(key.credentialId);
        var retained = mongo.findById(options.challengeId(), GovernanceBoundWebAuthnProof.class);
        var historical = new GovernanceBoundWebAuthnProofVerifier(new ObjectMapper());
        historical.verifyHistorical(retained, contract);
        for (var field : Map.of("rpId", "wrong.example", "origin", "https://wrong.example",
                "publicKeyCose", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("wrong-key".getBytes(StandardCharsets.UTF_8)),
                "requestJson", "{}", "contractJson", "{}").entrySet()) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(retained.id())),
                    new Update().set(field.getKey(), field.getValue()), GovernanceBoundWebAuthnProof.class);
            assertThatThrownBy(() -> historical.verifyHistorical(mongo.findById(retained.id(),
                    GovernanceBoundWebAuthnProof.class), contract)).isInstanceOf(SecurityException.class);
            mongo.save(retained);
        }
        String tamperedResponse = assertion.replace("clientDataJSON", "authenticatorData");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(retained.id())),
                new Update().set("assertionResponseBase64", Base64.getEncoder().encodeToString(
                        tamperedResponse.getBytes(StandardCharsets.UTF_8)))
                        .set("assertionSha256", GovernanceCanonicalJson.sha256(
                                tamperedResponse.getBytes(StandardCharsets.UTF_8))),
                GovernanceBoundWebAuthnProof.class);
        assertThatThrownBy(() -> historical.verifyHistorical(mongo.findById(retained.id(),
                GovernanceBoundWebAuthnProof.class), contract)).isInstanceOf(SecurityException.class);
        mongo.save(retained);
        for (String responseField : List.of("clientDataJSON", "authenticatorData")) {
            var altered = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(assertion);
            var response = (com.fasterxml.jackson.databind.node.ObjectNode) altered.get("response");
            byte[] bytes = Base64.getUrlDecoder().decode(response.get(responseField).asText());
            bytes[bytes.length - 1] ^= 1;
            response.put(responseField, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            String changed = json.writeValueAsString(altered);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(retained.id())),
                    new Update().set("assertionResponseBase64", Base64.getEncoder().encodeToString(
                            changed.getBytes(StandardCharsets.UTF_8)))
                            .set("assertionSha256", GovernanceCanonicalJson.sha256(
                                    changed.getBytes(StandardCharsets.UTF_8))),
                    GovernanceBoundWebAuthnProof.class);
            assertThatThrownBy(() -> historical.verifyHistorical(mongo.findById(retained.id(),
                    GovernanceBoundWebAuthnProof.class), contract)).isInstanceOf(SecurityException.class);
            mongo.save(retained);
        }
        assertThatThrownBy(() -> historical.verifyHistorical(retained,
                Map.of("domain", "com.frauddetection.model-promotion.review.v1",
                        "proposalId", "proposal-1", "modelArtifactSha256", "b".repeat(64),
                        "decision", "APPROVED"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> historical.verifyHistorical(retained,
                Map.of("domain", "com.frauddetection.policy-trust.change-review.v1",
                        "proposalId", "proposal-1", "modelArtifactSha256", "a".repeat(64),
                        "decision", "APPROVED"))).isInstanceOf(SecurityException.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(key.credentialId)),
                new Update().set("signatureCount", 9).set("revoked", true).set("status", "LOST")
                        .set("publicKeyCose", "rotated-key-no-longer-matches"),
                PolicyWebAuthnCredential.class);
        historical.verifyHistorical(mongo.findById(options.challengeId(),
                GovernanceBoundWebAuthnProof.class), contract);
        assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(author(),
                "model-promotion-review", options.challengeId(), assertion, contract)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void failedGovernanceAuditRollsBackBoundProofChallengeAndCounter() throws Exception {
        Authenticator key = register(author());
        Map<String, Object> contract = Map.of("domain", "com.frauddetection.model-promotion.review.v1",
                "proposalId", "proposal-rollback", "decision", "REJECTED");
        var options = webAuthn.startBoundAssertion(author(), "model-promotion-review", "rollback-1", contract);
        String assertion = key.assertion(options.challengeId(), ORIGIN, "localhost", true, 1);
        var transaction = new TransactionTemplate(new MongoTransactionManager(factory));
        assertThatThrownBy(() -> transaction.execute(ignored -> {
            webAuthn.acceptBoundAssertion(author(), "model-promotion-review", options.challengeId(),
                    assertion, contract);
            throw new IllegalStateException("injected audit write failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(mongo.findById(options.challengeId(), GovernanceBoundWebAuthnProof.class)).isNull();
        assertThat(mongo.findById(options.challengeId(), GovernanceWebAuthnBoundChallenge.class).used()).isFalse();
        assertThat(mongo.findById(key.credentialId, PolicyWebAuthnCredential.class).signatureCount()).isZero();
        transaction.execute(ignored -> webAuthn.acceptBoundAssertion(author(),
                "model-promotion-review", options.challengeId(), assertion, contract));
        assertThat(mongo.findById(options.challengeId(), GovernanceBoundWebAuthnProof.class)).isNotNull();
    }

    @Test
    void expiredOrCompromisedBoundCredentialCannotCommitNewProof() throws Exception {
        Authenticator key = register(author());
        Map<String, Object> contract = Map.of("domain", "com.frauddetection.model-promotion.review.v1",
                "proposalId", "proposal-expiry", "decision", "APPROVED");
        var transaction = new TransactionTemplate(new MongoTransactionManager(factory));
        var expired = webAuthn.startBoundAssertion(author(), "model-promotion-review", "expired-bound", contract);
        String expiredAssertion = key.assertion(expired.challengeId(), ORIGIN, "localhost", true, 1);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(expired.challengeId())),
                new Update().set("expiresAt", clock.instant().minusSeconds(1)),
                GovernanceWebAuthnBoundChallenge.class);
        assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(author(),
                "model-promotion-review", expired.challengeId(), expiredAssertion, contract)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(mongo.findById(expired.challengeId(), GovernanceBoundWebAuthnProof.class)).isNull();

        var compromised = webAuthn.startBoundAssertion(author(), "model-promotion-review",
                "compromised-bound", contract);
        String compromisedAssertion = key.assertion(compromised.challengeId(), ORIGIN, "localhost", true, 1);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(key.credentialId)),
                new Update().set("revoked", true).set("status", "COMPROMISED"),
                PolicyWebAuthnCredential.class);
        assertThatThrownBy(() -> transaction.execute(ignored -> webAuthn.acceptBoundAssertion(author(),
                "model-promotion-review", compromised.challengeId(), compromisedAssertion, contract)))
                .isInstanceOf(RuntimeException.class);
        assertThat(mongo.findById(compromised.challengeId(), GovernanceBoundWebAuthnProof.class)).isNull();
    }

    @Test
    void concurrentBoundAssertionsCommitOnlyOneProof() throws Exception {
        Authenticator key = register(author());
        Map<String, Object> contract = Map.of("domain", "com.frauddetection.model-promotion.review.v1",
                "proposalId", "proposal-race", "decision", "APPROVED");
        var options = webAuthn.startBoundAssertion(author(), "model-promotion-review", "review-race", contract);
        String assertion = key.assertion(options.challengeId(), ORIGIN, "localhost", true, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    new TransactionTemplate(new MongoTransactionManager(factory)).execute(ignored ->
                            webAuthn.acceptBoundAssertion(author(), "model-promotion-review",
                                    options.challengeId(), assertion, contract));
                    return true;
                } catch (RuntimeException rejected) {
                    return false;
                }
            })).toList();
            ready.await();
            go.countDown();
            assertThat(attempts.stream().filter(attempt -> {
                try { return attempt.get(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(mongo.count(new Query(), GovernanceBoundWebAuthnProof.class)).isEqualTo(1);
        assertThat(mongo.findById(options.challengeId(), GovernanceWebAuthnBoundChallenge.class).used()).isTrue();
        assertThat(mongo.findById(key.credentialId, PolicyWebAuthnCredential.class).signatureCount()).isEqualTo(1);
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
        return fixtureApproveAndClaim(target, id);
    }

    private String fixtureApproveAndClaim(PolicyGovernanceActor target, String id) {
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        String token = id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String digest;
        try {
            digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("approvedBy", credentialAdmin("security-operator-b").id())
                        .set("approvedAt", clock.instant())
                        .set("claimedAt", clock.instant())
                        .set("secretSha256", digest)
                        .set("expiresAt", clock.instant().plusSeconds(600)),
                PolicyCredentialEnrollmentGrantDocument.class);
        return token;
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
