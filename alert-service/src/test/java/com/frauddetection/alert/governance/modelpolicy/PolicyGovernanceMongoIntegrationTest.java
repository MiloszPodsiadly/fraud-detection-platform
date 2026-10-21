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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
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
class PolicyGovernanceMongoIntegrationTest extends AbstractIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");
    private static final String AUTHOR_ID = "oidc:" + "a".repeat(64);
    private static final String APPROVER_ID = "oidc:" + "b".repeat(64);
    private static final String PUBLISHER_ID = "oidc:" + "c".repeat(64);

    private SimpleMongoClientDatabaseFactory factory;
    private MongoTemplate mongo;
    private MutableClock clock;
    private MongoPolicyGovernanceStore store;
    private PolicyGovernanceService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        factory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb()
                .getReplicaSetUrl("policy_governance_" + UUID.randomUUID().toString().replace("-", "")));
        mongo = new MongoTemplate(factory);
        clock = new MutableClock(START);
        store = new MongoPolicyGovernanceStore(mongo,
                new TransactionTemplate(new MongoTransactionManager(factory)), clock);
        store.ensureIndexes();
        mapper = new ObjectMapper();
        service = new PolicyGovernanceService(store, new ModelPromotionPolicyValidator(mapper), mapper,
                "publisher-c", "publisher-key", "issuer-d", "issuer-key",
                org.mockito.Mockito.mock(com.frauddetection.alert.governance.modelpromotion.OnlinePolicyApproverDirectory.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongo != null) mongo.getDb().drop();
        if (factory != null) factory.destroy();
    }

    @Test
    void approvalUsesBackendAcceptanceClockAndDurableAtomicHistory() {
        PolicyProposalDocument created = service.create(author(), new DraftCommand("create-1", input("policy-1", false)));
        assertThat(created.status()).isEqualTo(PolicyGovernanceState.DRAFT);
        PolicyProposalDocument submitted = submit(author(), created.id(), new TransitionCommand("submit-1", 0));
        assertThat(submitted.draftSha256()).isEqualTo(created.draftSha256());
        clock.set(START.plusSeconds(73));
        PolicyProposalDocument approved = review(approver(), created.id(),
                new ReviewCommand("review-1", 1, ReviewDecision.APPROVE, "evidence:review-1"));
        assertThat(approved.status()).isEqualTo(PolicyGovernanceState.APPROVED);
        assertThat(approved.decidedAt()).isEqualTo(START.plusSeconds(73));
        assertThat(approved.draft().approvedContent(approved.decidedAt()).provenance().approvedAt())
                .isEqualTo(approved.decidedAt().toString());
        assertThat(service.history(auditor(), created.id())).extracting(PolicyGovernanceHistoryDocument::action)
                .containsExactly("CREATE_DRAFT", "SUBMIT_DRAFT", "REVIEW_APPROVE");
        assertThat(mongo.count(new Query(), PolicyGovernanceOperationDocument.class)).isEqualTo(3);
        assertThat(service.get(approver(), created.id())).isEqualTo(approved);
    }

    @Test
    void rejectionIsFinalAndCannotBecomePublicationRequest() {
        PolicyProposalDocument submitted = submitted("reject-1");
        PolicyProposalDocument rejected = review(approver(), submitted.id(),
                new ReviewCommand("review-reject", 1, ReviewDecision.REJECT, "evidence:reject"));
        assertThat(rejected.status()).isEqualTo(PolicyGovernanceState.REJECTED);
        assertThatThrownBy(() -> service.requestPublication(publisher(), submitted.id(),
                new TransitionCommand("request-rejected", 2))).isInstanceOf(PolicyGovernanceConflictException.class);
        assertThatThrownBy(() -> review(approver(), submitted.id(),
                new ReviewCommand("review-late", 2, ReviewDecision.APPROVE, "evidence:late")))
                .isInstanceOf(PolicyGovernanceConflictException.class);
    }

    @Test
    void selfApprovalAndMissingMandateFailClosed() {
        PolicyProposalDocument submitted = submitted("self-1");
        assertThatThrownBy(() -> review(authorWithApprovalGrant(), submitted.id(),
                new ReviewCommand("self-review", 1, ReviewDecision.APPROVE, "evidence:self")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> review(new PolicyGovernanceActor(APPROVER_ID, Set.of("ROLE_REVIEWER")),
                submitted.id(), new ReviewCommand("reviewer-role", 1, ReviewDecision.APPROVE, "evidence:role")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> review(new PolicyGovernanceActor(APPROVER_ID, Set.of("ROLE_FRAUD_OPS_ADMIN")),
                submitted.id(), new ReviewCommand("admin-role", 1, ReviewDecision.APPROVE, "evidence:admin")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(store.find(submitted.id()).status()).isEqualTo(PolicyGovernanceState.SUBMITTED);
    }

    @Test
    void productionRequiresSeparateScopeMandateForWritesAndReads() {
        DraftCommand production = new DraftCommand("prod-create", input("policy-prod", true));
        assertThatThrownBy(() -> service.create(author(), production)).isInstanceOf(AccessDeniedException.class);
        PolicyGovernanceActor authorized = new PolicyGovernanceActor(AUTHOR_ID,
                Set.of(ModelPolicyAuthority.CREATE, ModelPolicyAuthority.SUBMIT, ModelPolicyAuthority.READ,
                        ModelPolicyAuthority.PRODUCTION));
        PolicyProposalDocument created = service.create(authorized, production);
        assertThatThrownBy(() -> service.get(author(), created.id())).isInstanceOf(AccessDeniedException.class);
        assertThat(service.list(author())).isEmpty();
        assertThat(service.list(authorized)).hasSize(1);
        PolicyProposalDocument submitted = submit(authorized, created.id(),
                new TransitionCommand("prod-submit", 0));
        assertThatThrownBy(() -> review(approver(), submitted.id(),
                new ReviewCommand("prod-review-denied", 1, ReviewDecision.APPROVE, "evidence:prod")))
                .isInstanceOf(AccessDeniedException.class);
        PolicyGovernanceActor productionApprover = new PolicyGovernanceActor(APPROVER_ID,
                Set.of(ModelPolicyAuthority.APPROVE, ModelPolicyAuthority.PRODUCTION));
        assertThat(review(productionApprover, submitted.id(),
                new ReviewCommand("prod-review", 1, ReviewDecision.APPROVE, "evidence:prod")).status())
                .isEqualTo(PolicyGovernanceState.APPROVED);
    }

    @Test
    void identicalRetryAfterLostResponseReplaysAndConflictingOperationIsRejected() {
        DraftCommand command = new DraftCommand("retry-create", input("policy-retry", false));
        PolicyProposalDocument first = service.create(author(), command);
        PolicyProposalDocument replay = service.create(author(), command);
        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> service.create(author(),
                new DraftCommand("retry-create", input("different-policy", false))))
                .isInstanceOf(PolicyGovernanceConflictException.class);
        PolicyProposalDocument submitted = submit(author(), first.id(),
                new TransitionCommand("retry-submit", 0));
        assertThat(submit(author(), first.id(), new TransitionCommand("retry-submit", 0)))
                .isEqualTo(submitted);
        assertThat(service.history(auditor(), first.id())).hasSize(2);
    }

    @Test
    void approvedContentCannotBeEditedAndRestartRecoversDecision() {
        PolicyProposalDocument submitted = submitted("restart-1");
        PolicyProposalDocument approved = review(approver(), submitted.id(),
                new ReviewCommand("restart-review", 1, ReviewDecision.APPROVE, "evidence:restart"));
        assertThatThrownBy(() -> service.edit(author(), approved.id(),
                new UpdateDraftCommand("edit-approved", 2, input("restart-1", false))))
                .isInstanceOf(PolicyGovernanceConflictException.class);
        MongoPolicyGovernanceStore restarted = new MongoPolicyGovernanceStore(mongo,
                new TransactionTemplate(new MongoTransactionManager(factory)), clock);
        assertThat(restarted.find(approved.id())).isEqualTo(approved);
        assertThat(restarted.history(approved.id())).hasSize(3);
    }

    @Test
    void competingReviewersProduceOneFinalOutcome() throws Exception {
        PolicyProposalDocument submitted = submitted("race-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approve = executor.submit(() -> raceReview(ready, go, submitted.id(), "race-approve", ReviewDecision.APPROVE));
            var reject = executor.submit(() -> raceReview(ready, go, submitted.id(), "race-reject", ReviewDecision.REJECT));
            ready.await();
            go.countDown();
            List<Object> results = List.of(approve.get(), reject.get());
            assertThat(results.stream().filter(PolicyProposalDocument.class::isInstance).count()).isEqualTo(1);
        }
        PolicyProposalDocument durable = store.find(submitted.id());
        assertThat(durable.status()).isIn(PolicyGovernanceState.APPROVED, PolicyGovernanceState.REJECTED);
        assertThat(store.history(submitted.id())).hasSize(3);
    }

    @Test
    void opaqueLegacyReviewEvidenceCannotRequestPublication() {
        PolicyProposalDocument submitted = submitted("publish-1");
        assertThatThrownBy(() -> review(approver(), submitted.id(),
                new ReviewCommand("without-evidence", 1, ReviewDecision.APPROVE, "")))
                .isInstanceOf(IllegalArgumentException.class);
        PolicyProposalDocument approved = review(approver(), submitted.id(),
                new ReviewCommand("with-evidence", 1, ReviewDecision.APPROVE, "evidence:review"));
        assertThatThrownBy(() -> service.requestPublication(publisher(), approved.id(),
                new TransitionCommand("request-1", 2)))
                .isInstanceOf(PolicyGovernanceConflictException.class)
                .hasMessageContaining("WebAuthn");
        assertThat(store.find(approved.id()).status()).isEqualTo(PolicyGovernanceState.APPROVED);
    }

    @Test
    void missingPersistedEvidenceFailsClosedBeforePublicationRequest() {
        PolicyProposalDocument submitted = submitted("missing-evidence");
        PolicyProposalDocument approved = review(approver(), submitted.id(),
                new ReviewCommand("review-evidence", 1, ReviewDecision.APPROVE, "evidence:review"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(approved.id())),
                new Update().unset("approvalEvidenceReference"), PolicyProposalDocument.class);
        assertThatThrownBy(() -> service.requestPublication(publisher(), approved.id(),
                new TransitionCommand("request-missing-evidence", 2)))
                .isInstanceOf(PolicyGovernanceConflictException.class)
                .hasMessageContaining("evidence");
        assertThat(store.find(approved.id()).status()).isEqualTo(PolicyGovernanceState.APPROVED);
    }

    @Test
    void failedAuditInsertRollsBackStateAndOperationReceipt() {
        PolicyProposalDocument created = service.create(author(), new DraftCommand("create-rollback",
                input("policy-rollback", false)));
        mongo.insert(new PolicyGovernanceHistoryDocument(UUID.randomUUID().toString(), created.id(), 1,
                "BLOCKING_TEST_RECORD", PolicyGovernanceState.DRAFT, PolicyGovernanceState.SUBMITTED,
                AUTHOR_ID, "unrelated", "0".repeat(64), created.draftSha256(), null, START, null));
        assertThatThrownBy(() -> submit(author(), created.id(),
                new TransitionCommand("submit-rollback", 0))).isInstanceOf(PolicyGovernanceConflictException.class);
        assertThat(store.find(created.id()).status()).isEqualTo(PolicyGovernanceState.DRAFT);
        assertThat(mongo.findById("submit-rollback", PolicyGovernanceOperationDocument.class)).isNull();
    }

    @Test
    void draftEditsRequireOwnerAndExpectedVersionAndChangeFrozenDigest() {
        PolicyProposalDocument created = service.create(author(), new DraftCommand("create-edit",
                input("policy-edit", false)));
        DraftInput first = input("policy-edit", false);
        DraftInput amended = new DraftInput(first.policyId(), first.revision(), first.governanceScope(),
                first.intendedUse(), first.intendedApproverActorId(), first.approvalReference(),
                first.effectiveFromInclusive(), "2027-02-01T00:00:00Z", first.criteria());
        assertThatThrownBy(() -> service.edit(approver(), created.id(),
                new UpdateDraftCommand("forged-edit", 0, amended))).isInstanceOf(AccessDeniedException.class);
        PolicyProposalDocument edited = service.edit(author(), created.id(),
                new UpdateDraftCommand("edit-1", 0, amended));
        assertThat(edited.draftSha256()).isNotEqualTo(created.draftSha256());
        assertThatThrownBy(() -> service.edit(author(), created.id(),
                new UpdateDraftCommand("edit-stale", 0, amended))).isInstanceOf(PolicyGovernanceConflictException.class);
        PolicyProposalDocument submitted = submit(author(), created.id(), new TransitionCommand("submit-edit", 1));
        assertThat(submitted.draftSha256()).isEqualTo(edited.draftSha256());
    }

    private Object raceReview(CountDownLatch ready, CountDownLatch go, String id, String operation,
            ReviewDecision decision) {
        ready.countDown();
        try {
            go.await();
            return review(approver(), id, new ReviewCommand(operation, 1, decision, "evidence:" + operation));
        } catch (Exception failure) {
            return failure;
        }
    }

    private PolicyProposalDocument submitted(String policyId) {
        PolicyProposalDocument created = service.create(author(), new DraftCommand("create-" + policyId,
                input(policyId, false)));
        return submit(author(), created.id(), new TransitionCommand("submit-" + policyId, 0));
    }

    // These tests isolate the Mongo transition engine; the WebAuthn integration suite proves
    // that production creates this capability only after a verified assertion in the same transaction.
    private PolicyProposalDocument submit(PolicyGovernanceActor actor, String id, TransitionCommand command) {
        return service.submit(actor, id, command,
                proof(actor, id, command.operationId(), command.expectedVersion(), "POLICY_SUBMIT"));
    }

    private PolicyProposalDocument review(PolicyGovernanceActor actor, String id, ReviewCommand command) {
        return service.review(actor, id, command, proof(actor, id, command.operationId(),
                command.expectedVersion(), command.decision() == ReviewDecision.APPROVE
                        ? "POLICY_APPROVE" : "POLICY_REJECT"));
    }

    private PolicyWebAuthnService.VerifiedPolicyAction proof(PolicyGovernanceActor actor, String id,
            String operation, long expectedVersion, String action) {
        try {
            var constructor = PolicyWebAuthnService.VerifiedPolicyAction.class.getDeclaredConstructor(
                    String.class, String.class, String.class, long.class, String.class, Instant.class);
            constructor.setAccessible(true);
            return constructor.newInstance(actor.id(), id, operation, expectedVersion, action, clock.instant());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Verified transition fixture unavailable", failure);
        }
    }

    private PolicyGovernanceActor author() {
        return new PolicyGovernanceActor(AUTHOR_ID, Set.of(ModelPolicyAuthority.CREATE,
                ModelPolicyAuthority.SUBMIT, ModelPolicyAuthority.READ));
    }

    private PolicyGovernanceActor authorWithApprovalGrant() {
        return new PolicyGovernanceActor(AUTHOR_ID, Set.of(ModelPolicyAuthority.APPROVE));
    }

    private PolicyGovernanceActor approver() {
        return new PolicyGovernanceActor(APPROVER_ID, Set.of(ModelPolicyAuthority.APPROVE,
                ModelPolicyAuthority.REJECT, ModelPolicyAuthority.READ));
    }

    private PolicyGovernanceActor publisher() {
        return new PolicyGovernanceActor(PUBLISHER_ID, Set.of(ModelPolicyAuthority.PUBLISH_REQUEST));
    }

    private PolicyGovernanceActor auditor() {
        return new PolicyGovernanceActor(PUBLISHER_ID, Set.of(ModelPolicyAuthority.AUDIT_READ));
    }

    private DraftInput input(String policyId, boolean production) {
        List<ModelPromotionPolicy.CriterionRule> rules = Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(Collectors.toMap(
                                key -> key, key -> BigDecimal.ONE)), 86400, "VERIFIED",
                        criterion.evidenceSource(), criterion.metricSemantics(), criterion.populationDefinition(),
                        criterion.denominatorDefinition(), null)).toList();
        return new DraftInput(policyId, 1, new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                production ? ModelGovernanceEnvironment.PRODUCTION : ModelGovernanceEnvironment.TEST),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, APPROVER_ID, "review-1",
                "2026-12-01T00:00:00Z", "2027-01-01T00:00:00Z", rules);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private MutableClock(Instant instant) { this.instant = new AtomicReference<>(instant); }
        void set(Instant next) { instant.set(next); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
