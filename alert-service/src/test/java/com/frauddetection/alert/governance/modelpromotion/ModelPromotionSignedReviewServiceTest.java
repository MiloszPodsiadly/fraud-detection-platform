package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelPromotionSignedReviewServiceTest {
    private final MongoModelPromotionStore store = mock(MongoModelPromotionStore.class);
    private final ModelPromotionAuthorization authorization = mock(ModelPromotionAuthorization.class);
    private final ModelPromotionWorkflowService workflow = mock(ModelPromotionWorkflowService.class);
    private final PolicyWebAuthnService webAuthn = mock(PolicyWebAuthnService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ModelPromotionSignedReviewService service = new ModelPromotionSignedReviewService(
            store, authorization, workflow, webAuthn, mongo, new tools.jackson.databind.ObjectMapper());
    private final ExactModelArtifactIdentity identity = new ExactModelArtifactIdentity(
            "fraud_model", "v1", "features_v1", "a".repeat(64));
    private final ModelGovernanceScope scope = new ModelGovernanceScope(
            ModelGovernanceScope.CURRENT_INSTITUTION, ModelGovernanceEnvironment.PRODUCTION);
    private final ModelPromotionTransitionProposal transition = new ModelPromotionTransitionProposal(
            identity, scope, ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
            ModelPromotionLifecycleState.CANDIDATE, 0, ModelPromotionLifecycleState.CHALLENGER, "proposal-op");
    private final ModelPromotionProposalDocument proposal = mock(ModelPromotionProposalDocument.class);
    private final ModelPromotionProposal proposalBody = mock(ModelPromotionProposal.class);
    private final PolicyGovernanceActor reviewer = new PolicyGovernanceActor(
            PolicyGovernanceActor.identityId("https://issuer.example", "reviewer"), Set.of(),
            "https://issuer.example", "reviewer");
    private final ModelPromotionActorIdentity reviewerIdentity = ModelPromotionActorIdentity.institutional(
            reviewer.issuer(), reviewer.subject());
    private final ModelPromotionActorIdentity proposerIdentity = ModelPromotionActorIdentity.institutional(
            "https://issuer.example", "maker");
    private final String rationale = "Exact artifact and current evidence reviewed";
    private final ModelPromotionSignedReviewService.ReviewIntent intent =
            new ModelPromotionSignedReviewService.ReviewIntent("approval-1",
                    ModelPromotionApproval.Outcome.APPROVED, "review-1", rationale,
                    ModelPromotionDecisionRationale.sha256(rationale));

    @BeforeEach
    void setUp() {
        when(store.findProposal("proposal-1")).thenReturn(Optional.of(proposal));
        when(authorization.requireActor(AnalystAuthority.MODEL_PROMOTION_APPROVE, scope))
                .thenReturn(reviewer.id());
        when(proposal.proposalId()).thenReturn("proposal-1");
        when(proposal.transition()).thenReturn(transition);
        when(proposal.proposal()).thenReturn(proposalBody);
        when(proposalBody.proposedBy()).thenReturn(proposerIdentity.actorId());
        when(proposal.proposerIdentity()).thenReturn(proposerIdentity);
        when(proposal.reviewerIdentity()).thenReturn(reviewerIdentity);
        when(proposal.reviewStatus()).thenReturn(ModelPromotionProposalDocument.ReviewStatus.PENDING);
        when(proposal.commandSha256()).thenReturn("b".repeat(64));
        when(proposal.requestIntentSha256()).thenReturn("c".repeat(64));
        when(store.currentState(identity, scope, transition.intendedUse()))
                .thenReturn(Optional.of(ModelPromotionAggregate.registerCandidate(identity, scope,
                        transition.intendedUse())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void challengeBindsExactArtifactScopeRevisionTargetDecisionAndRationale() {
        when(webAuthn.startBoundAssertion(eq(reviewer), eq("model-promotion-review"), eq("review-1"), any()))
                .thenReturn(new PolicyWebAuthnService.CeremonyOptions(
                        "model-promotion-review:review-1", "{}", Instant.now()));
        service.start(reviewer, "proposal-1", intent);
        ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass(Map.class);
        verify(webAuthn).startBoundAssertion(eq(reviewer), eq("model-promotion-review"),
                eq("review-1"), captured.capture());
        Map<String, Object> contract = captured.getValue();
        assertThat(contract).containsEntry("modelArtifactSha256", "a".repeat(64))
                .containsEntry("proposalSha256", "b".repeat(64))
                .containsEntry("proposalIntentSha256", "c".repeat(64))
                .containsEntry("environment", "PRODUCTION")
                .containsEntry("requestedTargetState", "CHALLENGER")
                .containsEntry("currentRevision", 0L)
                .containsEntry("decision", "APPROVED")
                .containsEntry("rationaleSha256", ModelPromotionDecisionRationale.sha256(rationale));
    }

    @Test
    void makerAndChangedAssertionCannotUseSignedReviewReceipt() {
        var maker = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(
                "https://issuer.example", "maker"), Set.of(), "https://issuer.example", "maker");
        assertThatThrownBy(() -> service.start(maker, "proposal-1", intent))
                .isInstanceOf(AccessDeniedException.class);
        String challengeId = "model-promotion-review:review-1";
        var receipt = new ModelPromotionWebAuthnReviewEvidence(challengeId, "proposal-1", "review-1",
                "approval-1", ModelPromotionApproval.Outcome.APPROVED, intent.rationaleSha256(),
                reviewer.id(), reviewer.issuer(), reviewer.subject(), "credential-1",
                "d".repeat(64), ModelPromotionDecisionRationale.sha256("original-assertion"),
                1, Instant.now());
        when(mongo.findById(challengeId, ModelPromotionWebAuthnReviewEvidence.class)).thenReturn(receipt);
        assertThatThrownBy(() -> service.finish(reviewer, "proposal-1",
                new ModelPromotionSignedReviewService.SignedReview(intent, challengeId, "changed-assertion")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void legacyPendingProposalWithoutIssuerBindingMustBeResubmitted() {
        when(proposal.proposerIdentity()).thenReturn(null);

        assertThatThrownBy(() -> service.start(reviewer, "proposal-1", intent))
                .isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("resubmit");
    }

    @Test
    void reviewerWithSameSubjectFromAnotherIssuerCannotReview() {
        var collision = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(
                "https://other-issuer.example", "reviewer"), Set.of(),
                "https://other-issuer.example", "reviewer");

        assertThatThrownBy(() -> service.start(collision, "proposal-1", intent))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void receiptLookupRequiresExactOperationAndOriginalReviewer() {
        var approval = mock(ModelPromotionApproval.class);
        var approvalRationale = mock(ModelPromotionDecisionRationale.class);
        when(proposal.reviewOperationId()).thenReturn("review-1");
        when(proposal.approval()).thenReturn(approval);
        when(approval.outcome()).thenReturn(ModelPromotionApproval.Outcome.APPROVED);
        when(approval.approvedBy()).thenReturn(reviewer.id());
        when(approval.approvalId()).thenReturn("approval-1");
        when(approval.rationale()).thenReturn(approvalRationale);
        when(approvalRationale.sha256()).thenReturn(intent.rationaleSha256());
        var evidence = new ModelPromotionWebAuthnReviewEvidence("model-promotion-review:review-1",
                "proposal-1", "review-1", "approval-1", ModelPromotionApproval.Outcome.APPROVED,
                intent.rationaleSha256(), reviewer.id(), reviewer.issuer(), reviewer.subject(),
                "credential-1", "d".repeat(64), "e".repeat(64), 1, Instant.now());
        when(mongo.findById(evidence.challengeId(), ModelPromotionWebAuthnReviewEvidence.class))
                .thenReturn(evidence);
        assertThat(service.receipt(reviewer, "proposal-1", "review-1").rationaleSha256())
                .isEqualTo(intent.rationaleSha256());
        var mismatchedIssuer = new ModelPromotionWebAuthnReviewEvidence(evidence.challengeId(),
                evidence.proposalId(), evidence.operationId(), evidence.approvalId(), evidence.outcome(),
                evidence.rationaleSha256(), evidence.actorId(), "https://other-issuer.example",
                evidence.subject(), evidence.credentialId(), evidence.contractSha256(), evidence.assertionSha256(),
                evidence.signatureCount(), evidence.acceptedAt());
        when(mongo.findById(evidence.challengeId(), ModelPromotionWebAuthnReviewEvidence.class))
                .thenReturn(mismatchedIssuer);
        assertThatThrownBy(() -> service.receipt(reviewer, "proposal-1", "review-1"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.receipt(reviewer, "proposal-1", "other-operation"))
                .isInstanceOf(ResponseStatusException.class);
        var stranger = new PolicyGovernanceActor(PolicyGovernanceActor.identityId(
                "https://issuer.example", "stranger"), Set.of(), "https://issuer.example", "stranger");
        assertThatThrownBy(() -> service.receipt(stranger, "proposal-1", "review-1"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
