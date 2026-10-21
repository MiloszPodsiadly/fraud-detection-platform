package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Component
class ModelPromotionDecisionValidator {

    private final ObjectMapper objectMapper;
    private final ModelPromotionPolicyValidator policyValidator;
    private final ModelPromotionPolicyFullTrustVerifier fullTrustVerifier;

    ModelPromotionDecisionValidator(
            ObjectMapper objectMapper,
            ModelPromotionPolicyValidator policyValidator,
            ModelPromotionPolicyFullTrustVerifier fullTrustVerifier
    ) {
        this.objectMapper = objectMapper;
        this.policyValidator = policyValidator;
        this.fullTrustVerifier = fullTrustVerifier;
    }

    String canonicalSha256(Object value) {
        return GovernanceCanonicalJson.sha256(objectMapper, value);
    }

    String registrationCommandSha256(ModelPromotionRegistrationCommand command) {
        return canonicalSha256(new RegistrationIntent(
                command.artifactIdentity(), command.governanceScope(), command.intendedUse()
        ));
    }

    String transitionCommandSha256(DurableModelPromotionTransitionCommand request) {
        return canonicalSha256(new TransitionIntent(
                request.transition(),
                request.policySnapshot(),
                request.assessmentSnapshot(),
                request.proposal(),
                request.approval()
        ));
    }

    String proposalCommandSha256(ModelPromotionProposalSubmission submission) {
        ModelPromotionProposal proposal = submission.proposal();
        return canonicalSha256(new ProposalIntent(
                submission.transition(),
                submission.policySnapshot(),
                submission.assessmentSnapshot(),
                new ProposalContent(
                        proposal.proposalId(),
                        proposal.requestedState(),
                        proposal.proposedBy(),
                        proposal.rationale().sha256()
                )
        ));
    }

    String proposalRequestIntentSha256(ModelPromotionProposalRequestIntent intent) {
        return canonicalSha256(intent);
    }

    String reviewCommandSha256(ModelPromotionProposalReview review) {
        ModelPromotionApproval approval = review.approval();
        return canonicalSha256(new ReviewIntent(
                review.proposalId(),
                approval.approvalId(),
                approval.outcome(),
                approval.approvedBy(),
                approval.rationale().sha256()
        ));
    }

    void validateProposal(ModelPromotionProposalSubmission submission) {
        ModelPromotionTransitionProposal transition = submission.transition();
        Instant requestedAt = submission.operationContext().requestedAt();
        validatePolicySnapshot(
                submission.policySnapshot(),
                transition.governanceScope(),
                transition.intendedUse(),
                transition.requestedTargetState(),
                requestedAt
        );
        if (submission.proposal().requestedState() != transition.requestedTargetState()
                || submission.proposal().proposedAt().isAfter(requestedAt)) {
            throw new ModelPromotionPersistenceConflictException("Proposal does not match the requested transition");
        }
        validateAssessmentSnapshot(
                transition.artifactIdentity(),
                transition.governanceScope(),
                transition.intendedUse(),
                transition.requestedTargetState(),
                submission.policySnapshot(),
                submission.assessmentSnapshot(),
                requestedAt
        );
    }

    void validate(DurableModelPromotionTransitionCommand request) {
        ModelPromotionTransitionCommand transition = request.transition();
        Instant requestedAt = request.operationContext().requestedAt();
        validatePolicySnapshot(
                request.policySnapshot(),
                transition.governanceScope(),
                transition.intendedUse(),
                transition.requestedTargetState(),
                requestedAt
        );
        validateProposalAndApproval(request, requestedAt);
        validateAssessment(request, requestedAt);
    }

    private void validateProposalAndApproval(
            DurableModelPromotionTransitionCommand request,
            Instant requestedAt
    ) {
        ModelPromotionTransitionCommand transition = request.transition();
        ModelPromotionDecisionReference reference = transition.decisionReference();
        ModelPromotionProposal proposal = request.proposal();
        ModelPromotionApproval approval = request.approval();
        if (!reference.proposal().proposalId().equals(proposal.proposalId())
                || proposal.requestedState() != transition.requestedTargetState()) {
            throw new ModelPromotionPersistenceConflictException("Proposal does not match the requested transition");
        }
        if (!reference.approval().approvalId().equals(approval.approvalId())
                || !proposal.proposalId().equals(approval.proposalId())
                || approval.outcome() != ModelPromotionApproval.Outcome.APPROVED) {
            throw new ModelPromotionPersistenceConflictException("Approval does not authorize the requested proposal");
        }
        if (proposal.proposedAt().isAfter(approval.approvedAt()) || approval.approvedAt().isAfter(requestedAt)) {
            throw new ModelPromotionPersistenceConflictException("Proposal or approval timestamp is invalid");
        }
    }

    private void validateAssessment(
            DurableModelPromotionTransitionCommand request,
            Instant requestedAt
    ) {
        ModelPromotionDecisionReference reference = request.transition().decisionReference();
        ModelPromotionAssessment assessment = request.assessmentSnapshot();
        if (assessment == null) {
            if (reference.hasAssessment() || reference.hasEvidenceBundle()
                    || ModelPromotionEligibility.requiresAssessment(request.transition().requestedTargetState())) {
                throw new ModelPromotionPersistenceConflictException("Required assessment snapshot is unavailable");
            }
            return;
        }
        if (!reference.hasAssessment()
                || !reference.assessment().canonicalSha256().equals(assessment.canonicalSha256())
                || !reference.hasEvidenceBundle()
                || !reference.evidenceBundle().canonicalSha256().equals(
                        assessment.content().evidence().evidenceBundleSha256()
                )) {
            throw new ModelPromotionPersistenceConflictException("Assessment references do not match immutable snapshots");
        }
        ModelPromotionTransitionCommand transition = request.transition();
        validateAssessmentSnapshot(
                transition.artifactIdentity(),
                transition.governanceScope(),
                transition.intendedUse(),
                transition.requestedTargetState(),
                request.policySnapshot(),
                assessment,
                requestedAt
        );
    }

    private void validateAssessmentSnapshot(
            com.frauddetection.alert.governance.ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionLifecycleState target,
            ModelPromotionPolicy policy,
            ModelPromotionAssessment assessment,
            Instant requestedAt
    ) {
        if (assessment == null) {
            if (ModelPromotionEligibility.requiresAssessment(target)) {
                throw new ModelPromotionPersistenceConflictException("Required assessment snapshot is unavailable");
            }
            return;
        }
        if (policy == null) {
            throw new ModelPromotionPersistenceConflictException("Assessment requires a policy snapshot");
        }
        if (!canonicalSha256(assessment.content()).equals(assessment.canonicalSha256())) {
            throw new ModelPromotionPersistenceConflictException("Assessment snapshot digest is invalid");
        }
        ModelPromotionAssessment.Content content = assessment.content();
        if (!content.artifactIdentity().equals(artifactIdentity)
                || !content.governanceScope().equals(governanceScope)
                || content.intendedUse() != intendedUse
                || !content.policy().policyId().equals(policy.content().policyId())
                || !content.policy().revision().equals(policy.content().revision())
                || !content.policy().canonicalSha256().equals(policy.canonicalSha256())) {
            throw new ModelPromotionPersistenceConflictException("Assessment scope or policy snapshot is stale");
        }
        Instant assessedAt = GovernanceTimestampContract.parse(content.assessmentCreatedAt(), "assessmentCreatedAt");
        if (assessedAt.isAfter(requestedAt) || assessmentExpired(content, requestedAt)) {
            throw new ModelPromotionPersistenceConflictException("Assessment snapshot is stale");
        }
        if (ModelPromotionEligibility.requiresAssessment(target) && !content.humanReviewable()) {
            throw new ModelPromotionPersistenceConflictException("Assessment is not human-reviewable");
        }
        if (ModelPromotionEligibility.requiresAssessment(target) && !ModelPromotionEligibility.passed(target, content.criteria())) {
            throw new ModelPromotionPersistenceConflictException("Assessment is missing mandatory promotion criteria");
        }
        if ((target == ModelPromotionLifecycleState.PROMOTION_RECOMMENDED
                || target == ModelPromotionLifecycleState.CHAMPION)
                && !content.formalRecommendationEligible()) {
            throw new ModelPromotionPersistenceConflictException("Assessment is not eligible for formal recommendation");
        }
        if ((target == ModelPromotionLifecycleState.PROMOTION_RECOMMENDED
                || target == ModelPromotionLifecycleState.CHAMPION)
                && policy.content().provenance().authority()
                != ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED) {
            throw new ModelPromotionPersistenceConflictException(
                    "Formal promotion requires an institutional policy snapshot"
            );
        }
    }

    private boolean assessmentExpired(ModelPromotionAssessment.Content content, Instant requestedAt) {
        Instant evidenceEnd = GovernanceTimestampContract.parse(
                content.evidence().evaluationWindow().toInclusive(),
                "evaluationWindow.toInclusive"
        );
        return content.criteria().stream()
                .filter(criterion -> criterion.maximumEvidenceAgeSeconds() != null)
                .anyMatch(criterion -> evidenceEnd.plusSeconds(criterion.maximumEvidenceAgeSeconds()).isBefore(requestedAt));
    }

    private void validatePolicySnapshot(
            ModelPromotionPolicy policy,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionLifecycleState target,
            Instant requestedAt
    ) {
        if (policy == null) {
            if (ModelPromotionEligibility.requiresAssessment(target)) {
                throw new ModelPromotionPersistenceConflictException("Quality-gated transition requires a policy snapshot");
            }
            return;
        }
        policyValidator.validate(policy, governanceScope, intendedUse, requestedAt);
        if (ModelPromotionEligibility.requiresAssessment(target)
                && governanceScope.environment() == ModelGovernanceEnvironment.PRODUCTION
                && policy.content().provenance().authority()
                == ModelPromotionPolicy.PolicyAuthority.NON_INSTITUTIONAL_TEST) {
            throw new ModelPromotionPersistenceConflictException(
                    "A non-institutional test policy cannot authorize a production quality-gated transition"
            );
        }
        if (policy.content().provenance().authority()
                == ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED
                && fullTrustVerifier.verify(policy).status()
                != ModelPromotionPolicyTrustVerification.Status.FULLY_AUTHORIZED) {
            throw new ModelPromotionPersistenceConflictException(
                    "Institutional policy authority could not be verified"
            );
        }
    }

    private record RegistrationIntent(
            com.frauddetection.alert.governance.ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse
    ) {
    }

    private record TransitionIntent(
            ModelPromotionTransitionCommand transition,
            ModelPromotionPolicy policySnapshot,
            ModelPromotionAssessment assessmentSnapshot,
            ModelPromotionProposal proposal,
            ModelPromotionApproval approval
    ) {
    }

    private record ProposalIntent(
            ModelPromotionTransitionProposal transition,
            ModelPromotionPolicy policySnapshot,
            ModelPromotionAssessment assessmentSnapshot,
            ProposalContent proposal
    ) {
    }

    private record ProposalContent(
            String proposalId,
            ModelPromotionLifecycleState requestedState,
            String proposedBy,
            String rationaleSha256
    ) {
    }

    private record ReviewIntent(
            String proposalId,
            String approvalId,
            ModelPromotionApproval.Outcome outcome,
            String approvedBy,
            String rationaleSha256
    ) {
    }
}
