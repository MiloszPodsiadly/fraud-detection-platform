package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelPromotionWorkflowServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");
    private static final String RATIONALE = "a".repeat(64);
    private static final String AUTHORIZATION = "b".repeat(64);

    private final MongoModelPromotionStore store = mock(MongoModelPromotionStore.class);
    private final ModelPromotionEvidenceResolver evidenceResolver = mock(ModelPromotionEvidenceResolver.class);
    private final ModelPromotionAssessmentService assessmentService = mock(ModelPromotionAssessmentService.class);
    private final ModelPromotionPolicyResolver policyResolver = mock(ModelPromotionPolicyResolver.class);
    private ModelPromotionWorkflowService service;

    @BeforeEach
    void setUp() {
        ModelPromotionAuthorization authorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        service = new ModelPromotionWorkflowService(
                store,
                evidenceResolver,
                assessmentService,
                policyResolver,
                authorization,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsUnauthenticatedCandidateRegistration() {
        assertThatThrownBy(() -> service.registerCandidate(new ModelPromotionWorkflowService.CandidateRegistrationRequest(
                identity(), scope(ModelGovernanceEnvironment.PRODUCTION), use(), "register-1"
        )))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(exception -> assertThat(((ModelPromotionWorkflowBlockedException) exception).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.UNAUTHENTICATED));
    }

    @Test
    void bindsSubmittedProposalToTheAuthenticatedPrincipalInsteadOfRequestPayload() {
        authenticate("proposer", AnalystRole.ANALYST.authorities(), Set.of(AnalystRole.ANALYST));
        ModelPromotionPolicy policy = ModelPromotionAssessmentTestFixtures.policy();
        when(policyResolver.resolve(scope(ModelGovernanceEnvironment.PRODUCTION), use(), NOW))
                .thenReturn(ModelPromotionPolicyResolution.available(policy));
        when(store.submitProposal(any())).thenAnswer(invocation -> {
            ModelPromotionProposalSubmission submission = invocation.getArgument(0);
            return new ModelPromotionProposalResult(
                    submission.proposal().proposalId(),
                    ModelPromotionProposalDocument.ReviewStatus.PENDING,
                    submission.proposal(),
                    null,
                    submission.policySnapshot().canonicalSha256(),
                    submission.assessmentSnapshot() == null ? null : submission.assessmentSnapshot().canonicalSha256(),
                    NOW,
                    null,
                    false
            );
        });

        ModelPromotionProposalResult result = service.proposeTransition(
                new ModelPromotionWorkflowService.TransitionProposalRequest(
                        identity(),
                        scope(ModelGovernanceEnvironment.PRODUCTION),
                        use(),
                        ModelPromotionLifecycleState.CANDIDATE,
                        0,
                        ModelPromotionLifecycleState.CHALLENGER,
                        window(),
                        "proposal-operation",
                        "proposal-1",
                        RATIONALE
                )
        );

        assertThat(result.proposal().proposedBy()).isEqualTo("proposer");
        org.mockito.ArgumentCaptor<ModelPromotionProposalSubmission> submitted =
                org.mockito.ArgumentCaptor.forClass(ModelPromotionProposalSubmission.class);
        org.mockito.Mockito.verify(store).submitProposal(submitted.capture());
        assertThat(submitted.getValue().operationContext().actorId()).isEqualTo("proposer");
    }

    @Test
    void rejectsAdministratorSelfApproval() {
        ModelPromotionProposalDocument proposal = pendingProposal(
                "admin-user", ModelGovernanceEnvironment.PRODUCTION
        );
        when(store.findProposal(proposal.proposalId())).thenReturn(Optional.of(proposal));
        authenticate("admin-user", AnalystRole.FRAUD_OPS_ADMIN.authorities(), Set.of(AnalystRole.FRAUD_OPS_ADMIN));

        assertThatThrownBy(() -> service.reviewProposal(reviewRequest(proposal.proposalId())))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("cannot review their own proposal");
    }

    @Test
    void rejectsCrossEnvironmentApprovalWhenPrincipalLacksThatScopeAuthority() {
        ModelPromotionProposalDocument proposal = pendingProposal(
                "proposer", ModelGovernanceEnvironment.PRODUCTION
        );
        when(store.findProposal(proposal.proposalId())).thenReturn(Optional.of(proposal));
        authenticate(
                "reviewer",
                Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST),
                Set.of()
        );

        assertThatThrownBy(() -> service.reviewProposal(reviewRequest(proposal.proposalId())))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(exception -> assertThat(((ModelPromotionWorkflowBlockedException) exception).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.UNAUTHORIZED));
    }

    @Test
    void rejectsApprovalWhenTheReviewerLosesAuthorityBeforeCommit() {
        ModelPromotionProposalDocument proposal = pendingProposal(
                "proposer", ModelGovernanceEnvironment.PRODUCTION
        );
        when(store.findProposal(proposal.proposalId())).thenReturn(Optional.of(proposal));
        when(policyResolver.resolve(scope(ModelGovernanceEnvironment.PRODUCTION), use(), NOW))
                .thenReturn(ModelPromotionPolicyResolution.available(proposal.policySnapshot()));
        when(store.reviewProposal(any(), any())).thenAnswer(invocation -> {
            authenticate(
                    "reviewer",
                    Set.of(AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION),
                    Set.of()
            );
            ((Runnable) invocation.getArgument(1)).run();
            return proposal.toResult(false);
        });
        authenticate(
                "reviewer",
                Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION),
                Set.of()
        );

        assertThatThrownBy(() -> service.reviewProposal(reviewRequest(proposal.proposalId())))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(exception -> assertThat(((ModelPromotionWorkflowBlockedException) exception).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.UNAUTHORIZED));
    }

    @Test
    void permitsIndependentProposalRejectionWhenCurrentPolicyIsUnavailable() {
        ModelPromotionProposalDocument proposal = pendingProposal(
                "proposer", ModelGovernanceEnvironment.PRODUCTION
        );
        when(store.findProposal(proposal.proposalId())).thenReturn(Optional.of(proposal));
        when(store.reviewProposal(any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return proposal.toResult(false);
        });
        authenticate(
                "reviewer",
                Set.of(AnalystAuthority.MODEL_PROMOTION_REJECT, AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION),
                Set.of()
        );

        ModelPromotionProposalResult result = service.reviewProposal(new ModelPromotionWorkflowService.ReviewRequest(
                proposal.proposalId(),
                "rejection-1",
                ModelPromotionApproval.Outcome.REJECTED,
                "reject-operation",
                RATIONALE
        ));

        assertThat(result.proposalId()).isEqualTo(proposal.proposalId());
    }

    private ModelPromotionProposalDocument pendingProposal(
            String proposer,
            ModelGovernanceEnvironment environment
    ) {
        ModelPromotionTransitionProposal transition = new ModelPromotionTransitionProposal(
                identity(),
                scope(environment),
                use(),
                ModelPromotionLifecycleState.CANDIDATE,
                0,
                ModelPromotionLifecycleState.CHALLENGER,
                "proposal-operation"
        );
        ModelPromotionOperationContext context = new ModelPromotionOperationContext(
                "proposal-operation", proposer, AUTHORIZATION, NOW
        );
        ModelPromotionProposalSubmission submission = new ModelPromotionProposalSubmission(
                transition,
                context,
                ModelPromotionAssessmentTestFixtures.policy(),
                null,
                new ModelPromotionProposal(
                        "proposal-1",
                        ModelPromotionLifecycleState.CHALLENGER,
                        proposer,
                        NOW,
                        RATIONALE
                )
        );
        return ModelPromotionProposalDocument.pending(
                "aggregate-key",
                "c".repeat(64),
                submission,
                new ModelPromotionDecisionDocument.LocalAuditEvidence(
                        "audit-1", "SUBMIT_MODEL_PROMOTION_PROPOSAL", "SUCCEEDED", proposer, AUTHORIZATION, NOW
                ),
                NOW
        );
    }

    private ModelPromotionWorkflowService.ReviewRequest reviewRequest(String proposalId) {
        return new ModelPromotionWorkflowService.ReviewRequest(
                proposalId,
                "approval-1",
                ModelPromotionApproval.Outcome.APPROVED,
                "review-operation",
                RATIONALE
        );
    }

    private void authenticate(String userId, Set<String> authorities, Set<AnalystRole> roles) {
        AnalystPrincipal principal = new AnalystPrincipal(userId, roles, authorities);
        SecurityContextHolder.getContext().setAuthentication(new AnalystAuthenticationFactory().authenticated(principal));
    }

    private ExactModelArtifactIdentity identity() {
        return new ExactModelArtifactIdentity(
                "fraud-model", "2026.10", "feature-contract-v2", "d".repeat(64)
        );
    }

    private ModelGovernanceScope scope(ModelGovernanceEnvironment environment) {
        return new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, environment);
    }

    private ModelPromotionIntendedUse use() {
        return ModelPromotionIntendedUse.FRAUD_RISK_SCORING;
    }

    private ModelPromotionEvaluationWindow window() {
        return new ModelPromotionEvaluationWindow("2026-06-01T00:00:00Z", "2026-06-09T23:59:59Z");
    }
}
