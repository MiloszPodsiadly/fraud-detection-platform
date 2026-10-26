package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
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
    private static final String RATIONALE = "The transition is justified by the reviewed operational evidence.";
    private static final String RATIONALE_SHA256 = ModelPromotionDecisionRationale.sha256(RATIONALE);
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
        authenticate("proposer", withProductionScope(AnalystRole.ANALYST.authorities()), Set.of(AnalystRole.ANALYST));
        ModelPromotionPolicy policy = ModelPromotionAssessmentTestFixtures.policy();
        when(policyResolver.resolve(scope(ModelGovernanceEnvironment.PRODUCTION), use(), NOW))
                .thenReturn(ModelPromotionPolicyResolution.available(policy));
        when(store.submitProposal(any(), any())).thenAnswer(invocation -> {
            ModelPromotionProposalSubmission submission = invocation.getArgument(0);
            return new ModelPromotionProposalResult(
                    submission.proposal().proposalId(),
                    ModelPromotionProposalDocument.ReviewStatus.PENDING,
                    submission.proposal(),
                    null,
                    submission.policySnapshot() == null ? null : submission.policySnapshot().canonicalSha256(),
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
                        RATIONALE,
                        RATIONALE_SHA256
                )
        );

        String proposerId = PolicyGovernanceActor.identityId("https://issuer.example", "proposer");
        assertThat(result.proposal().proposedBy()).isEqualTo(proposerId);
        org.mockito.ArgumentCaptor<ModelPromotionProposalSubmission> submitted =
                org.mockito.ArgumentCaptor.forClass(ModelPromotionProposalSubmission.class);
        org.mockito.Mockito.verify(store).submitProposal(submitted.capture(), any());
        assertThat(submitted.getValue().operationContext().actorId()).isEqualTo(proposerId);
        assertThat(submitted.getValue().operationContext().actorIdentity().issuer()).isEqualTo("https://issuer.example");
        assertThat(submitted.getValue().operationContext().actorIdentity().subject()).isEqualTo("proposer");
        assertThat(submitted.getValue().policySnapshot()).isNull();
        assertThat(submitted.getValue().assessmentSnapshot()).isNull();
    }

    @Test
    void authorizationFingerprintUsesStableIssuerSubjectAndRightsInsteadOfDisplayLogin() {
        String issuer = "https://issuer.example";
        String subject = "stable-subject";
        Set<String> authorities = Set.of(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST, AnalystAuthority.MODEL_PROMOTION_RATIONALE_READ);
        ModelPromotionAuthorization authorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        authenticate(new AnalystPrincipal("display one", Set.of(), authorities, issuer + "#" + subject,
                PolicyGovernanceActor.identityId(issuer, subject)));
        var first = authorization.authorize(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                scope(ModelGovernanceEnvironment.TEST), "operation-1", NOW);

        authenticate(new AnalystPrincipal("display two", Set.of(), authorities, issuer + "#" + subject,
                PolicyGovernanceActor.identityId(issuer, subject)));
        var sameActor = authorization.authorize(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                scope(ModelGovernanceEnvironment.TEST), "operation-1", NOW);
        assertThat(sameActor.actorId()).isEqualTo(first.actorId());
        assertThat(sameActor.authorizationFingerprint()).isEqualTo(first.authorizationFingerprint());

        String otherIssuer = "https://other-issuer.example";
        authenticate(new AnalystPrincipal("display two", Set.of(), authorities,
                otherIssuer + "#" + subject, PolicyGovernanceActor.identityId(otherIssuer, subject)));
        assertThatThrownBy(() -> authorization.revalidate(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                scope(ModelGovernanceEnvironment.TEST), first.operationContext()))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class);

        Set<String> reducedRights = Set.of(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST);
        authenticate(new AnalystPrincipal("display two", Set.of(), reducedRights, issuer + "#" + subject,
                PolicyGovernanceActor.identityId(issuer, subject)));
        assertThatThrownBy(() -> authorization.revalidate(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                scope(ModelGovernanceEnvironment.TEST), first.operationContext()))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class);
    }

    @Test
    void localDemoIdentityCannotSatisfyProductionPromotionAuthorization() {
        ModelPromotionAuthorization authorization = new ModelPromotionAuthorization(new CurrentAnalystUser());
        String localActorId = "local:" + "a".repeat(64);
        authenticate(new AnalystPrincipal("demo display", Set.of(),
                withProductionScope(Set.of(AnalystAuthority.MODEL_PROMOTION_PROPOSE)), null, localActorId));

        assertThatThrownBy(() -> authorization.authorize(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                scope(ModelGovernanceEnvironment.PRODUCTION), "operation-demo", NOW))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(error -> assertThat(((ModelPromotionWorkflowBlockedException) error).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.UNSUPPORTED_PRINCIPAL));
    }

    @Test
    void nonInstitutionalTestPolicyCannotOpenProductionShadowApproval() {
        authenticate("proposer", Set.of(AnalystAuthority.MODEL_PROMOTION_PROPOSE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION), Set.of());
        ModelPromotionPolicy testPolicy = ModelPromotionAssessmentTestFixtures.policy(
                scope(ModelGovernanceEnvironment.PRODUCTION));
        when(policyResolver.resolve(scope(ModelGovernanceEnvironment.PRODUCTION), use(), NOW))
                .thenReturn(ModelPromotionPolicyResolution.available(testPolicy));

        assertThatThrownBy(() -> service.proposeTransition(new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity(), scope(ModelGovernanceEnvironment.PRODUCTION), use(),
                ModelPromotionLifecycleState.CHALLENGER, 1, ModelPromotionLifecycleState.SHADOW_APPROVED,
                window(), "test-policy-operation", "test-policy-proposal", RATIONALE, RATIONALE_SHA256)))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(exception -> assertThat(((ModelPromotionWorkflowBlockedException) exception).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.POLICY_UNAVAILABLE));
        org.mockito.Mockito.verifyNoInteractions(evidenceResolver, assessmentService);
    }

    @Test
    void rejectsAdministratorSelfApproval() {
        ModelPromotionProposalDocument proposal = pendingProposal(
                "admin-user", ModelGovernanceEnvironment.PRODUCTION
        );
        when(store.findProposal(proposal.proposalId())).thenReturn(Optional.of(proposal));
        authenticate(
                "admin-user", withProductionScope(AnalystRole.FRAUD_OPS_ADMIN.authorities()),
                Set.of(AnalystRole.FRAUD_OPS_ADMIN)
        );

        assertThatThrownBy(() -> service.reviewProposal(reviewRequest(proposal.proposalId())))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("cannot review their own proposal");
    }

    @Test
    void unsignedProductionReviewAndFormattedEmergencyRationaleAreDenied() {
        ModelPromotionProposalDocument ordinary = pendingProposal("proposer", ModelGovernanceEnvironment.PRODUCTION);
        when(store.findProposal(ordinary.proposalId())).thenReturn(Optional.of(ordinary));
        authenticate("reviewer", Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION), Set.of());
        assertThatThrownBy(() -> service.reviewProposal(reviewRequest(ordinary.proposalId())))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("WebAuthn");

        ModelPromotionProposalDocument retirement = pendingProposal("proposer",
                ModelGovernanceEnvironment.PRODUCTION, ModelPromotionLifecycleState.RETIRED);
        when(store.findProposal(retirement.proposalId())).thenReturn(Optional.of(retirement));
        String rationale = "BREAK_GLASS:case123:Emergency containment required immediately";
        var emergency = new ModelPromotionWorkflowService.ReviewRequest(retirement.proposalId(),
                "retirement-approval", ModelPromotionApproval.Outcome.APPROVED,
                "retirement-review", rationale, ModelPromotionDecisionRationale.sha256(rationale));
        assertThatThrownBy(() -> service.reviewProposal(emergency))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("WebAuthn");
        authenticate("reviewer", Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE,
                AnalystAuthority.MODEL_PROMOTION_RETIRE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION), Set.of());
        assertThatThrownBy(() -> service.reviewProposal(emergency))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("WebAuthn");
    }

    @Test
    void rejectsProductionProposalForOrdinaryAnalystRole() {
        authenticate("proposer", AnalystRole.ANALYST.authorities(), Set.of(AnalystRole.ANALYST));

        assertThatThrownBy(() -> service.proposeTransition(new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity(),
                scope(ModelGovernanceEnvironment.PRODUCTION),
                use(),
                ModelPromotionLifecycleState.CANDIDATE,
                0,
                ModelPromotionLifecycleState.REJECTED,
                window(),
                "production-operation",
                "production-proposal",
                RATIONALE,
                RATIONALE_SHA256
        )))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(exception -> assertThat(((ModelPromotionWorkflowBlockedException) exception).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.UNAUTHORIZED));
    }

    @Test
    void submitsEmergencyRetirementWithoutPolicyOrEvidence() {
        authenticate(
                "retirement-proposer",
                Set.of(AnalystAuthority.MODEL_PROMOTION_RETIRE, AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION),
                Set.of()
        );
        when(store.submitProposal(any(), any())).thenAnswer(invocation -> {
            ModelPromotionProposalSubmission submission = invocation.getArgument(0);
            return new ModelPromotionProposalResult(
                    submission.proposal().proposalId(),
                    ModelPromotionProposalDocument.ReviewStatus.PENDING,
                    submission.proposal(),
                    null,
                    null,
                    null,
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
                        ModelPromotionLifecycleState.CHAMPION,
                        4,
                        ModelPromotionLifecycleState.RETIRED,
                        window(),
                        "retirement-operation",
                        "retirement-proposal",
                        RATIONALE,
                        RATIONALE_SHA256
                )
        );

        assertThat(result.proposal().rationale().content()).isEqualTo(RATIONALE);
        org.mockito.ArgumentCaptor<ModelPromotionProposalSubmission> submitted =
                org.mockito.ArgumentCaptor.forClass(ModelPromotionProposalSubmission.class);
        org.mockito.Mockito.verify(store).submitProposal(submitted.capture(), any());
        assertThat(submitted.getValue().policySnapshot()).isNull();
        assertThat(submitted.getValue().assessmentSnapshot()).isNull();
        org.mockito.Mockito.verifyNoInteractions(policyResolver, evidenceResolver, assessmentService);
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
        when(store.reviewProposal(any(), any(), any(), any())).thenAnswer(invocation -> {
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

        assertThatThrownBy(() -> service.reviewProposal(reviewRequest(proposal.proposalId()), () -> {}))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(exception -> assertThat(((ModelPromotionWorkflowBlockedException) exception).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.UNAUTHORIZED));
    }

    @Test
    void rejectsApprovalWhenThePolicyChangedAfterTheQualityGatedProposalWasSubmitted() {
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionPolicy submittedPolicy = ModelPromotionAssessmentTestFixtures.policy();
        ModelPromotionProposalDocument proposal = pendingQualityGatedProposal("proposer", submittedPolicy, evidence);
        ModelPromotionPolicy changedPolicy = ModelPromotionAssessmentTestFixtures.policy(
                ModelPromotionAssessmentTestFixtures.defaultRules(),
                "2026-02-01T00:00:00Z",
                "2027-01-01T00:00:00Z"
        );
        when(store.findProposal(proposal.proposalId())).thenReturn(Optional.of(proposal));
        when(policyResolver.resolve(scope(ModelGovernanceEnvironment.PRODUCTION), use(), NOW))
                .thenReturn(ModelPromotionPolicyResolution.available(changedPolicy));
        reviewProposalRunsPreconditions(proposal);
        authenticate(
                "reviewer",
                Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION),
                Set.of()
        );

        assertThatThrownBy(() -> service.reviewProposal(reviewRequest(proposal.proposalId()), () -> {}))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(exception -> assertThat(((ModelPromotionWorkflowBlockedException) exception).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.POLICY_CHANGED));
    }

    @Test
    void rejectsApprovalWhenTheExactAssessmentEvidenceChangedAfterProposalSubmission() {
        ModelPromotionEvidenceBundle submittedEvidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionPolicy policy = ModelPromotionAssessmentTestFixtures.policy();
        ModelPromotionProposalDocument proposal = pendingQualityGatedProposal("proposer", policy, submittedEvidence);
        when(store.findProposal(proposal.proposalId())).thenReturn(Optional.of(proposal));
        when(policyResolver.resolve(scope(ModelGovernanceEnvironment.PRODUCTION), use(), NOW))
                .thenReturn(ModelPromotionPolicyResolution.available(policy));
        when(evidenceResolver.resolve(any(), any(), any(), any()))
                .thenReturn(ModelPromotionEvidenceResolution.available(
                        ModelPromotionAssessmentTestFixtures.currentLimitedEvidence()
                ));
        reviewProposalRunsPreconditions(proposal);
        authenticate(
                "reviewer",
                Set.of(AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION),
                Set.of()
        );

        assertThatThrownBy(() -> service.reviewProposal(reviewRequest(proposal.proposalId()), () -> {}))
                .isInstanceOf(ModelPromotionWorkflowBlockedException.class)
                .satisfies(exception -> assertThat(((ModelPromotionWorkflowBlockedException) exception).reason())
                        .isEqualTo(ModelPromotionWorkflowBlockedException.Reason.EVIDENCE_CHANGED));
    }

    @Test
    void permitsIndependentProposalRejectionWhenCurrentPolicyIsUnavailable() {
        ModelPromotionProposalDocument proposal = pendingProposal(
                "proposer", ModelGovernanceEnvironment.PRODUCTION
        );
        when(store.findProposal(proposal.proposalId())).thenReturn(Optional.of(proposal));
        when(store.reviewProposal(any(), any(), any(), any())).thenAnswer(invocation -> {
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
                RATIONALE,
                RATIONALE_SHA256
        ), () -> {});

        assertThat(result.proposalId()).isEqualTo(proposal.proposalId());
    }

    @Test
    void submitsAdministrativeRejectionWithoutPolicyOrEvidence() {
        authenticate("proposer", withProductionScope(AnalystRole.ANALYST.authorities()), Set.of(AnalystRole.ANALYST));
        when(store.submitProposal(any(), any())).thenAnswer(invocation -> {
            ModelPromotionProposalSubmission submission = invocation.getArgument(0);
            return new ModelPromotionProposalResult(
                    submission.proposal().proposalId(),
                    ModelPromotionProposalDocument.ReviewStatus.PENDING,
                    submission.proposal(),
                    null,
                    null,
                    null,
                    NOW,
                    null,
                    false
            );
        });

        service.proposeTransition(new ModelPromotionWorkflowService.TransitionProposalRequest(
                identity(),
                scope(ModelGovernanceEnvironment.PRODUCTION),
                use(),
                ModelPromotionLifecycleState.CANDIDATE,
                0,
                ModelPromotionLifecycleState.REJECTED,
                window(),
                "reject-operation",
                "reject-proposal",
                RATIONALE,
                RATIONALE_SHA256
        ));

        org.mockito.ArgumentCaptor<ModelPromotionProposalSubmission> submitted =
                org.mockito.ArgumentCaptor.forClass(ModelPromotionProposalSubmission.class);
        org.mockito.Mockito.verify(store).submitProposal(submitted.capture(), any());
        assertThat(submitted.getValue().policySnapshot()).isNull();
        assertThat(submitted.getValue().assessmentSnapshot()).isNull();
        org.mockito.Mockito.verifyNoInteractions(policyResolver, evidenceResolver, assessmentService);
    }

    private ModelPromotionProposalDocument pendingProposal(
            String proposer,
            ModelGovernanceEnvironment environment
    ) {
        return pendingProposal(proposer, environment, ModelPromotionLifecycleState.CHALLENGER);
    }

    private ModelPromotionProposalDocument pendingProposal(
            String proposer, ModelGovernanceEnvironment environment,
            ModelPromotionLifecycleState targetState
    ) {
        ModelPromotionTransitionProposal transition = new ModelPromotionTransitionProposal(
                identity(),
                scope(environment),
                use(),
                targetState == ModelPromotionLifecycleState.RETIRED
                        ? ModelPromotionLifecycleState.CHAMPION : ModelPromotionLifecycleState.CANDIDATE,
                targetState == ModelPromotionLifecycleState.RETIRED ? 4 : 0,
                targetState,
                "proposal-operation"
        );
        ModelPromotionActorIdentity proposerIdentity = ModelPromotionActorIdentity.institutional(
                "https://issuer.example", proposer);
        ModelPromotionOperationContext context = new ModelPromotionOperationContext(
                "proposal-operation", proposerIdentity.actorId(), AUTHORIZATION, NOW, proposerIdentity
        );
        ModelPromotionProposalSubmission submission = new ModelPromotionProposalSubmission(
                transition,
                context,
                targetState == ModelPromotionLifecycleState.RETIRED
                        ? null : ModelPromotionAssessmentTestFixtures.policy(),
                null,
                new ModelPromotionProposal(
                        "proposal-1",
                        targetState,
                        proposerIdentity.actorId(),
                        NOW,
                        proposalRationale("proposal-1")
                )
        );
        return ModelPromotionProposalDocument.pending(
                "aggregate-key",
                "c".repeat(64),
                submission,
                new ModelPromotionDecisionDocument.LocalAuditEvidence(
                        "audit-1", "SUBMIT_MODEL_PROMOTION_PROPOSAL", "SUCCEEDED",
                        proposerIdentity.actorId(), AUTHORIZATION, NOW
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
                RATIONALE,
                RATIONALE_SHA256
        );
    }

    private void reviewProposalRunsPreconditions(ModelPromotionProposalDocument proposal) {
        when(store.reviewProposal(any(), any(), any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return proposal.toResult(false);
        });
    }

    private ModelPromotionProposalDocument pendingQualityGatedProposal(
            String proposer,
            ModelPromotionPolicy policy,
            ModelPromotionEvidenceBundle evidence
    ) {
        ModelPromotionAssessment assessment = ModelPromotionAssessmentTestFixtures.service(
                (governanceScope, intendedUse, assessmentTime) -> Optional.of(policy)
        ).assess(evidence, NOW);
        ModelPromotionTransitionProposal transition = new ModelPromotionTransitionProposal(
                evidence.content().artifactIdentity(),
                scope(ModelGovernanceEnvironment.PRODUCTION),
                use(),
                ModelPromotionLifecycleState.CHALLENGER,
                1,
                ModelPromotionLifecycleState.SHADOW_APPROVED,
                "quality-gated-proposal-operation"
        );
        ModelPromotionActorIdentity proposerIdentity = ModelPromotionActorIdentity.institutional(
                "https://issuer.example", proposer);
        ModelPromotionProposalSubmission submission = new ModelPromotionProposalSubmission(
                transition,
                new ModelPromotionOperationContext(
                        "quality-gated-proposal-operation", proposerIdentity.actorId(), AUTHORIZATION, NOW,
                        proposerIdentity
                ),
                policy,
                assessment,
                new ModelPromotionProposal(
                        "quality-gated-proposal",
                        ModelPromotionLifecycleState.SHADOW_APPROVED,
                        proposerIdentity.actorId(),
                        NOW,
                        proposalRationale("quality-gated-proposal")
                )
        );
        return ModelPromotionProposalDocument.pending(
                "quality-gated-aggregate-key",
                "c".repeat(64),
                submission,
                new ModelPromotionDecisionDocument.LocalAuditEvidence(
                        "quality-gated-audit",
                        "SUBMIT_MODEL_PROMOTION_PROPOSAL",
                        "SUCCEEDED",
                        proposerIdentity.actorId(),
                        AUTHORIZATION,
                        NOW
                ),
                NOW
        );
    }

    private void authenticate(String userId, Set<String> authorities, Set<AnalystRole> roles) {
        String issuer = "https://issuer.example";
        String actorId = PolicyGovernanceActor.identityId(issuer, userId);
        AnalystPrincipal principal = new AnalystPrincipal("display-" + userId, roles, authorities,
                issuer + "#" + userId, actorId);
        authenticate(principal);
    }

    private void authenticate(AnalystPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new AnalystAuthenticationFactory().authenticated(principal));
    }

    private Set<String> withProductionScope(Set<String> authorities) {
        java.util.LinkedHashSet<String> scopedAuthorities = new java.util.LinkedHashSet<>(authorities);
        scopedAuthorities.add(AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION);
        return scopedAuthorities;
    }

    private ModelPromotionDecisionRationale proposalRationale(String proposalId) {
        return new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.proposalReference(proposalId), RATIONALE, RATIONALE_SHA256
        );
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
