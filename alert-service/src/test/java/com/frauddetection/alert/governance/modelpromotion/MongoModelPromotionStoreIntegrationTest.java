package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionCapabilityProbe;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("invariant-proof")
@Tag("integration")
class MongoModelPromotionStoreIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");
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
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    @Test
    void committedRegistrationAndTransitionSurviveRestartAndReplayDeterministically() {
        ExactModelArtifactIdentity identity = identity("a");
        ModelPromotionMutationResult registration = store.registerCandidate(registration(identity, scope("PRODUCTION"), "register-a"));
        DurableModelPromotionTransitionCommand command = transition(
                identity, scope("PRODUCTION"), ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHALLENGER, "challenger-a", false
        );
        ModelPromotionMutationResult committed = store.transition(command);

        MongoModelPromotionStore restarted = store(new ModelPromotionLocalAuditFactory(), validator);
        DurableModelPromotionTransitionCommand retriedAfterLostResponse = new DurableModelPromotionTransitionCommand(
                command.transition(),
                new ModelPromotionOperationContext(
                        command.operationContext().operationId(),
                        command.operationContext().actorId(),
                        command.operationContext().authorizationFingerprint(),
                        NOW.plusSeconds(30)
                ),
                command.policySnapshot(),
                command.assessmentSnapshot(),
                command.proposal(),
                command.approval()
        );
        ModelPromotionMutationResult replay = restarted.transition(retriedAfterLostResponse);

        assertThat(registration.aggregate().lifecycleState()).isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(committed.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.aggregate()).isEqualTo(committed.aggregate());
        assertThat(restarted.currentState(identity, scope("PRODUCTION"), use())).contains(committed.aggregate());
        assertThat(restarted.history(identity, scope("PRODUCTION"), use(), 10)).hasSize(2);
    }

    @Test
    void modifiedDuplicateAndConcurrentStaleRevisionCannotOverwriteCommittedState() throws Exception {
        ExactModelArtifactIdentity identity = identity("b");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-b"));
        DurableModelPromotionTransitionCommand first = transition(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHALLENGER, "shared-operation", false
        );
        store.transition(first);

        DurableModelPromotionTransitionCommand modified = transition(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.REJECTED, "shared-operation", false
        );
        assertThatThrownBy(() -> store.transition(modified))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("different canonical content");

        DurableModelPromotionTransitionCommand stale = transition(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.REJECTED, "stale-operation", false
        );
        assertThatThrownBy(() -> store.transition(stale)).isInstanceOf(IllegalStateException.class);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(2);
    }

    @Test
    void auditFailureRollsBackStateAndDecisionHistory() {
        ExactModelArtifactIdentity identity = identity("c");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-c"));
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

        assertThatThrownBy(() -> failing.transition(transition(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHALLENGER, "audit-failure", false
        ))).hasMessageContaining("local audit unavailable");

        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
    }

    @Test
    void databaseEnforcesSingleChampionDuringConcurrentTransitions() throws Exception {
        ModelGovernanceScope scope = scope("PRODUCTION");
        ExactModelArtifactIdentity first = identity("d");
        ExactModelArtifactIdentity second = identity("e");
        advanceToPromotionRecommended(first, scope, "first");
        advanceToPromotionRecommended(second, scope, "second");
        DurableModelPromotionTransitionCommand firstCommand = transition(
                first, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-first", true
        );
        DurableModelPromotionTransitionCommand secondCommand = transition(
                second, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-second", true
        );

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> attemptAfter(start, firstCommand),
                    () -> attemptAfter(start, secondCommand)
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

        store.transition(transition(
                production, productionScope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-production", true
        ));
        store.transition(transition(
                test, testScope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-test", true
        ));

        assertThat(mongoTemplate.count(new Query(), ModelPromotionChampionClaimDocument.class)).isEqualTo(2);
    }

    @Test
    void invalidTransitionLeavesCurrentStateAndImmutableHistoryUnchanged() {
        ExactModelArtifactIdentity identity = identity("2");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-invalid"));

        assertThatThrownBy(() -> store.transition(transition(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHAMPION, "invalid-direct-champion", true
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

        assertThatThrownBy(() -> store.transition(transition(
                identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHALLENGER, "outage-transition", false
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
    }

    @Test
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

        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CHALLENGER);
        assertThat(store.findProposal("proposal-2").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.APPROVED);
    }

    @Test
    void rejectedProposalLeavesLifecycleUnchangedAndAppendsDurableDecisionHistory() {
        ExactModelArtifactIdentity identity = identity("6");
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-rejection"));
        store.submitProposal(proposal(identity, scope, "proposal-rejection", "proposal-3", "proposer"));

        ModelPromotionProposalResult rejected = store.reviewProposal(
                review("proposal-3", "reject-proposal", "reviewer", ModelPromotionApproval.Outcome.REJECTED),
                () -> {
                }
        );

        assertThat(rejected.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.REJECTED);
        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(store.history(identity, scope, use(), 10))
                .extracting(ModelPromotionDecisionDocument::eventType)
                .containsExactly(
                        ModelPromotionDecisionDocument.EventType.CANDIDATE_REGISTERED,
                        ModelPromotionDecisionDocument.EventType.TRANSITION_PROPOSAL_REJECTED
                );
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

    private boolean attemptAfter(CountDownLatch start, DurableModelPromotionTransitionCommand command) throws Exception {
        start.await();
        try {
            store.transition(command);
            return true;
        } catch (ModelPromotionPersistenceConflictException expected) {
            return false;
        }
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

    private void advanceToPromotionRecommended(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            String suffix
    ) {
        store.registerCandidate(registration(identity, scope, "register-" + suffix));
        store.transition(transition(identity, scope, ModelPromotionLifecycleState.CANDIDATE, 0,
                ModelPromotionLifecycleState.CHALLENGER, "challenger-" + suffix, false));
        store.transition(transition(identity, scope, ModelPromotionLifecycleState.CHALLENGER, 1,
                ModelPromotionLifecycleState.SHADOW_APPROVED, "shadow-" + suffix, true));
        store.transition(transition(identity, scope, ModelPromotionLifecycleState.SHADOW_APPROVED, 2,
                ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, "recommend-" + suffix, true));
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

    private void createRequiredIndexes() {
        mongoTemplate.indexOps(ModelPromotionStateDocument.class).ensureIndex(
                new Index()
                        .on("model_name", Sort.Direction.ASC)
                        .on("model_version", Sort.Direction.ASC)
                        .on("feature_contract_version", Sort.Direction.ASC)
                        .on("model_artifact_sha256", Sort.Direction.ASC)
                        .on("institutional_scope", Sort.Direction.ASC)
                        .on("environment", Sort.Direction.ASC)
                        .on("intended_use", Sort.Direction.ASC)
                        .unique().named(ModelPromotionStateDocument.IDENTITY_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC).on("operation_id", Sort.Direction.ASC)
                        .unique().named(ModelPromotionDecisionDocument.OPERATION_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).ensureIndex(
                new Index().on("decision_id", Sort.Direction.ASC).unique().sparse()
                        .named(ModelPromotionDecisionDocument.DECISION_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionDecisionDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC)
                        .on("committed_at", Sort.Direction.DESC)
                        .on("operation_id", Sort.Direction.ASC)
                        .named(ModelPromotionDecisionDocument.HISTORY_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionProposalDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC).on("operation_id", Sort.Direction.ASC)
                        .unique().named(ModelPromotionProposalDocument.OPERATION_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionProposalDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC)
                        .on("review_status", Sort.Direction.ASC)
                        .on("submitted_at", Sort.Direction.DESC)
                        .named(ModelPromotionProposalDocument.PENDING_REVIEW_INDEX)
        );
        mongoTemplate.indexOps(ModelPromotionProposalDocument.class).ensureIndex(
                new Index().on("aggregate_key", Sort.Direction.ASC)
                        .on("submitted_at", Sort.Direction.DESC)
                        .on("_id", Sort.Direction.ASC)
                        .named(ModelPromotionProposalDocument.HISTORY_INDEX)
        );
        if (!mongoTemplate.collectionExists(ModelPromotionChampionClaimDocument.COLLECTION)) {
            mongoTemplate.createCollection(ModelPromotionChampionClaimDocument.COLLECTION);
        }
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
        ModelPromotionTransitionProposal transition = new ModelPromotionTransitionProposal(
                identity,
                scope,
                use(),
                ModelPromotionLifecycleState.CANDIDATE,
                0,
                ModelPromotionLifecycleState.CHALLENGER,
                operationId
        );
        ModelPromotionOperationContext context = context(operationId, proposer);
        return new ModelPromotionProposalSubmission(
                transition,
                context,
                ModelPromotionAssessmentTestFixtures.policy(),
                null,
                new ModelPromotionProposal(
                        proposalId,
                        ModelPromotionLifecycleState.CHALLENGER,
                        proposer,
                        NOW.minusSeconds(30),
                        RATIONALE
                )
        );
    }

    private ModelPromotionProposalReview review(String proposalId, String operationId, String reviewer) {
        return review(proposalId, operationId, reviewer, ModelPromotionApproval.Outcome.APPROVED);
    }

    private ModelPromotionProposalReview review(
            String proposalId,
            String operationId,
            String reviewer,
            ModelPromotionApproval.Outcome outcome
    ) {
        ModelPromotionOperationContext context = context(operationId, reviewer);
        return new ModelPromotionProposalReview(
                proposalId,
                new ModelPromotionApproval(
                        "approval-" + operationId,
                        proposalId,
                        outcome,
                        reviewer,
                        NOW.minusSeconds(10),
                        RATIONALE
                ),
                context
        );
    }

    private DurableModelPromotionTransitionCommand transition(
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            ModelPromotionLifecycleState source,
            long revision,
            ModelPromotionLifecycleState target,
            String operationId,
            boolean withAssessmentReferences
    ) {
        String suffix = operationId.replaceAll("[^A-Za-z0-9]", "");
        ModelPromotionDecisionReference reference = new ModelPromotionDecisionReference(
                "decision-" + suffix,
                new ModelPromotionDecisionReference.ProposalReference("proposal-" + suffix),
                withAssessmentReferences
                        ? new ModelPromotionDecisionReference.EvidenceBundleReference("evidence-" + suffix, "6".repeat(64))
                        : null,
                withAssessmentReferences
                        ? new ModelPromotionDecisionReference.AssessmentReference("assessment-" + suffix, "7".repeat(64))
                        : null,
                new ModelPromotionDecisionReference.ApprovalReference("approval-" + suffix)
        );
        ModelPromotionTransitionCommand transition = new ModelPromotionTransitionCommand(
                identity, scope, use(), source, revision, target, operationId, reference
        );
        ModelPromotionProposal proposal = new ModelPromotionProposal(
                reference.proposal().proposalId(), target, "proposer", NOW.minusSeconds(120), RATIONALE
        );
        ModelPromotionApproval approval = new ModelPromotionApproval(
                reference.approval().approvalId(), proposal.proposalId(), ModelPromotionApproval.Outcome.APPROVED,
                "approver", NOW.minusSeconds(60), RATIONALE
        );
        return new DurableModelPromotionTransitionCommand(
                transition, context(operationId), ModelPromotionAssessmentTestFixtures.policy(), null, proposal, approval
        );
    }

    private ModelPromotionOperationContext context(String operationId) {
        return context(operationId, "governance-actor");
    }

    private ModelPromotionOperationContext context(String operationId, String actorId) {
        return new ModelPromotionOperationContext(operationId, actorId, AUTHORIZATION, NOW);
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
            super(objectMapper, new ModelPromotionPolicyValidator(objectMapper));
        }

        @Override
        void validate(DurableModelPromotionTransitionCommand request) {
            // These integration proofs isolate Mongo transaction, CAS, idempotency and uniqueness behavior.
        }
    }
}
