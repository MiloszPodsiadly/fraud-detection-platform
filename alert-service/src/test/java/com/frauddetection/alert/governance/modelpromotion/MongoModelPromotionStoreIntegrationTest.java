package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionCapabilityProbe;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

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
    void committedQualityProposalReplaysFromMongoBeforePolicyAndEvidenceAfterRestart() {
        ExactModelArtifactIdentity identity = ModelPromotionAssessmentTestFixtures.identity();
        ModelGovernanceScope scope = scope("PRODUCTION");
        store.registerCandidate(registration(identity, scope, "register-replay"));
        store.submitProposal(proposal(identity, scope, "propose-challenger", "proposal-challenger", "proposer"));
        store.reviewProposal(review("proposal-challenger", "approve-challenger", "reviewer"), () -> {
        });

        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionPolicy policy = ModelPromotionAssessmentTestFixtures.policy();
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
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION
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
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION
        ));
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(request))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class);
        authenticate("proposer", Set.of(AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
        assertThatThrownBy(() -> recoveredWorkflow.proposeTransition(request))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(error -> assertThat(((ModelPromotionWorkflowBlockedException) error).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.UNAUTHORIZED));
        assertThat(store.findProposal("proposal-shadow").orElseThrow().reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(store.proposals(identity, scope, use(), 10)).hasSize(2);

        authenticate("proposer", Set.of(
                AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION
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
                        NOW.plusSeconds(30)
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
    }

    @Test
    void emergencyRetirementByIndependentReviewerReleasesChampionWithoutPolicyEvidence() {
        ExactModelArtifactIdentity identity = identity("a");
        ModelGovernanceScope scope = scope("PRODUCTION");
        advanceToPromotionRecommended(identity, scope, "retire");
        acceptTransition(identity, scope, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, 3,
                ModelPromotionLifecycleState.CHAMPION, "champion-retire");
        ModelPromotionProposalSubmission retirement = retirementProposal(
                identity, scope, "retirement-operation", "retirement-proposal", "retirement-proposer"
        );

        ModelPromotionProposalResult pending = store.submitProposal(retirement);
        ModelPromotionProposalResult approved = store.reviewProposal(
                review("retirement-proposal", "retirement-review", "retirement-reviewer"), () -> {
                }
        );

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
        assertThat(decision.approval().approvedBy()).isEqualTo("retirement-reviewer");
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
                }
        )).hasMessageContaining("local review audit unavailable");

        assertThat(store.currentState(identity, scope, use()).orElseThrow().lifecycleState())
                .isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        ModelPromotionProposalDocument pending = store.findProposal("proposal-review-audit-failure").orElseThrow();
        assertThat(pending.reviewStatus()).isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        assertThat(pending.approval()).isNull();
        assertThat(store.history(identity, scope, use(), 10)).hasSize(1);
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
    }

    @Test
    void rejectionDecisionInsertFailureRollsBackReviewStatus() {
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
                .hasCauseInstanceOf(org.springframework.dao.DuplicateKeyException.class);

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
                        proposer,
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
                        proposer,
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
                        reviewer,
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
                NOW
        );
    }

    private ModelPromotionOperationContext context(String operationId, String actorId) {
        return new ModelPromotionOperationContext(operationId, actorId, AUTHORIZATION, NOW);
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
        AnalystPrincipal principal = new AnalystPrincipal(actorId, Set.of(), authorities);
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
                    policy -> ModelPromotionPolicyAuthorityVerification.verified()
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
