package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionDecisionValidatorTest {

    private static final String RATIONALE = "8".repeat(64);
    private static final String AUTHORIZATION = "9".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelPromotionPolicyValidator policyValidator = new ModelPromotionPolicyValidator(objectMapper);
    private final ModelPromotionDecisionValidator validator = new ModelPromotionDecisionValidator(
            objectMapper, policyValidator
    );

    @Test
    void acceptsCurrentInstitutionalAssessmentBoundToExactPolicyAndEvidence() {
        ModelPromotionPolicy policy = institutionalPolicy("2027-01-01T00:00:00Z");
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionAssessment assessment = assessment(policy, evidence);

        assertThatCode(() -> validator.validate(command(
                policy, assessment, evidence, Instant.parse("2026-06-11T12:00:00Z")
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsExpiredPolicyAndStaleAssessment() {
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionPolicy expiredPolicy = institutionalPolicy("2026-06-11T06:00:00Z");
        ModelPromotionAssessment expiredAssessment = assessment(expiredPolicy, evidence);
        assertThatThrownBy(() -> validator.validate(command(
                expiredPolicy, expiredAssessment, evidence, Instant.parse("2026-06-11T12:00:00Z")
        ))).isInstanceOf(RuntimeException.class);

        ModelPromotionPolicy currentPolicy = institutionalPolicy("2027-01-01T00:00:00Z");
        ModelPromotionAssessment staleAssessment = assessment(currentPolicy, evidence);
        assertThatThrownBy(() -> validator.validate(command(
                currentPolicy, staleAssessment, evidence, Instant.parse("2026-06-20T00:00:00Z")
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("stale");
    }

    private ModelPromotionPolicy institutionalPolicy(String effectiveTo) {
        ModelPromotionPolicy.Content content = new ModelPromotionPolicy.Content(
                ModelPromotionPolicy.CURRENT_VERSION,
                "institutional-policy",
                1,
                ModelPromotionAssessmentTestFixtures.scope(),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                new ModelPromotionPolicy.PolicyProvenance(
                        ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        "governance-author",
                        "model-risk-committee",
                        "2025-12-31T00:00:00Z",
                        "approval-reference"
                ),
                "2026-01-01T00:00:00Z",
                effectiveTo,
                ModelPromotionAssessmentTestFixtures.defaultRules()
        );
        return new ModelPromotionPolicy(content, policyValidator.canonicalSha256(content));
    }

    private ModelPromotionAssessment assessment(
            ModelPromotionPolicy policy,
            ModelPromotionEvidenceBundle evidence
    ) {
        ModelPromotionAssessmentService service = ModelPromotionAssessmentTestFixtures.service(
                (scope, use) -> java.util.Optional.of(policy)
        );
        return service.assess(evidence, ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME);
    }

    private DurableModelPromotionTransitionCommand command(
            ModelPromotionPolicy policy,
            ModelPromotionAssessment assessment,
            ModelPromotionEvidenceBundle evidence,
            Instant requestedAt
    ) {
        ModelPromotionDecisionReference reference = new ModelPromotionDecisionReference(
                "decision-shadow",
                new ModelPromotionDecisionReference.ProposalReference("proposal-shadow"),
                new ModelPromotionDecisionReference.EvidenceBundleReference(
                        "evidence-shadow", evidence.canonicalSha256()
                ),
                new ModelPromotionDecisionReference.AssessmentReference(
                        "assessment-shadow", assessment.canonicalSha256()
                ),
                new ModelPromotionDecisionReference.ApprovalReference("approval-shadow")
        );
        ModelPromotionTransitionCommand transition = new ModelPromotionTransitionCommand(
                evidence.content().artifactIdentity(),
                evidence.content().governanceScope(),
                evidence.content().intendedUse(),
                ModelPromotionLifecycleState.CHALLENGER,
                1,
                ModelPromotionLifecycleState.SHADOW_APPROVED,
                "shadow-operation",
                reference
        );
        ModelPromotionOperationContext context = new ModelPromotionOperationContext(
                transition.operationId(), "governance-actor", AUTHORIZATION, requestedAt
        );
        return new DurableModelPromotionTransitionCommand(
                transition,
                context,
                policy,
                assessment,
                new ModelPromotionProposal(
                        reference.proposal().proposalId(),
                        transition.requestedTargetState(),
                        "proposer",
                        requestedAt.minusSeconds(120),
                        RATIONALE
                ),
                new ModelPromotionApproval(
                        reference.approval().approvalId(),
                        reference.proposal().proposalId(),
                        ModelPromotionApproval.Outcome.APPROVED,
                        "approver",
                        requestedAt.minusSeconds(60),
                        RATIONALE
                )
        );
    }
}
