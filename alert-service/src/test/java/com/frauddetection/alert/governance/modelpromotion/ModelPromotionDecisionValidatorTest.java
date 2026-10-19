package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionDecisionValidatorTest {

    private static final String RATIONALE = "8".repeat(64);
    private static final String AUTHORIZATION = "9".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelPromotionPolicyValidator policyValidator = new ModelPromotionPolicyValidator(objectMapper);
    private final ModelPromotionDecisionValidator validator = new ModelPromotionDecisionValidator(
            objectMapper,
            policyValidator,
            policy -> ModelPromotionPolicyAuthorityVerification.verified()
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

    @Test
    void testPolicyCannotAuthorizeProductionChampion() {
        ModelPromotionPolicy testPolicy = ModelPromotionAssessmentTestFixtures.policy();
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionAssessment assessment = assessment(testPolicy, evidence);

        assertThatThrownBy(() -> validator.validate(command(
                testPolicy,
                assessment,
                evidence,
                Instant.parse("2026-06-11T12:00:00Z"),
                ModelPromotionLifecycleState.PROMOTION_RECOMMENDED,
                3,
                ModelPromotionLifecycleState.CHAMPION
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class);
    }

    @Test
    void forgedEligibleAssessmentCannotUseTestPolicyForFormalRecommendation() {
        ModelPromotionPolicy testPolicy = ModelPromotionAssessmentTestFixtures.policy();
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionAssessment.Content original = assessment(testPolicy, evidence).content();
        ModelPromotionAssessment.Content forged = new ModelPromotionAssessment.Content(
                original.assessmentVersion(), original.assessmentCreatedAt(), original.evaluatorIdentity(),
                original.evaluatorVersion(), original.artifactIdentity(), original.governanceScope(),
                original.intendedUse(), original.policy(), original.evidence(), original.populationAndCoverage(),
                original.criteria(), ModelPromotionAssessment.AssessmentStatus.ELIGIBLE_FOR_FORMAL_RECOMMENDATION,
                original.diagnosticEvidenceAvailable(), true, true, false, false,
                original.blockers(), original.limitations()
        );
        ModelPromotionAssessment tamperedAssessment = new ModelPromotionAssessment(
                forged, validator.canonicalSha256(forged)
        );

        assertThatThrownBy(() -> validator.validate(command(
                testPolicy,
                tamperedAssessment,
                evidence,
                Instant.parse("2026-06-11T12:00:00Z"),
                ModelPromotionLifecycleState.SHADOW_APPROVED,
                2,
                ModelPromotionLifecycleState.PROMOTION_RECOMMENDED
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("institutional policy");
    }

    @Test
    void validInstitutionalPolicyDoesNotBypassUnavailableMandatoryEvidence() {
        ModelPromotionPolicy policy = institutionalPolicy("2027-01-01T00:00:00Z");
        ModelPromotionAssessment assessment = assessment(
                policy,
                ModelPromotionAssessmentTestFixtures.currentLimitedEvidence()
        );

        assertThat(assessment.content().formalRecommendationEligible()).isFalse();
        assertThat(assessment.content().blockers())
                .contains("PRECISION_AT_ALERT_REVIEW_BUDGET:MATCHED_RULES_PRECISION_AT_REVIEW_BUDGET_UNAVAILABLE");
    }

    @Test
    void missingMandatoryAssessmentCriterionBlocksTheTransition() {
        ModelPromotionPolicy policy = institutionalPolicy("2027-01-01T00:00:00Z");
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionAssessment.Content original = assessment(policy, evidence).content();
        ModelPromotionAssessment.Content incomplete = new ModelPromotionAssessment.Content(
                original.assessmentVersion(), original.assessmentCreatedAt(), original.evaluatorIdentity(),
                original.evaluatorVersion(), original.artifactIdentity(), original.governanceScope(),
                original.intendedUse(), original.policy(), original.evidence(), original.populationAndCoverage(),
                original.criteria().stream()
                        .filter(criterion -> criterion.criterion() != ModelPromotionCriterion.EXACT_SHA_MODEL_CARD)
                        .toList(),
                original.assessmentStatus(), original.diagnosticEvidenceAvailable(), original.humanReviewable(),
                original.formalRecommendationEligible(), false, false, original.blockers(), original.limitations()
        );
        ModelPromotionAssessment tamperedAssessment = new ModelPromotionAssessment(
                incomplete, validator.canonicalSha256(incomplete)
        );

        assertThatThrownBy(() -> validator.validate(command(
                policy, tamperedAssessment, evidence, Instant.parse("2026-06-11T12:00:00Z")
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("mandatory promotion criteria");
    }

    @Test
    void durableDecisionRevalidatesInstitutionalPolicyAuthority() {
        ModelPromotionPolicy policy = institutionalPolicy("2027-01-01T00:00:00Z");
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionAssessment assessment = assessment(policy, evidence);
        ModelPromotionDecisionValidator unavailableAuthorityValidator = new ModelPromotionDecisionValidator(
                objectMapper,
                policyValidator,
                ignored -> ModelPromotionPolicyAuthorityVerification.unavailable()
        );

        assertThatThrownBy(() -> unavailableAuthorityValidator.validate(command(
                policy,
                assessment,
                evidence,
                Instant.parse("2026-06-11T12:00:00Z")
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("authority could not be verified");
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
        String digest = policyValidator.canonicalSha256(content);
        return new ModelPromotionPolicy(
                content,
                digest,
                new ModelPromotionPolicy.PolicyAuthorizationEvidence(
                        "institutional-policy-authority",
                        "test-key",
                        "test-signature",
                        digest,
                        "2025-12-31T00:00:00Z"
                )
        );
    }

    private ModelPromotionAssessment assessment(
            ModelPromotionPolicy policy,
            ModelPromotionEvidenceBundle evidence
    ) {
        ModelPromotionAssessmentService service = ModelPromotionAssessmentTestFixtures.service(
                (scope, use, assessmentTime) -> java.util.Optional.of(policy)
        );
        return service.assess(evidence, ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME);
    }

    private DurableModelPromotionTransitionCommand command(
            ModelPromotionPolicy policy,
            ModelPromotionAssessment assessment,
            ModelPromotionEvidenceBundle evidence,
            Instant requestedAt
    ) {
        return command(
                policy,
                assessment,
                evidence,
                requestedAt,
                ModelPromotionLifecycleState.CHALLENGER,
                1,
                ModelPromotionLifecycleState.SHADOW_APPROVED
        );
    }

    private DurableModelPromotionTransitionCommand command(
            ModelPromotionPolicy policy,
            ModelPromotionAssessment assessment,
            ModelPromotionEvidenceBundle evidence,
            Instant requestedAt,
            ModelPromotionLifecycleState sourceState,
            long expectedRevision,
            ModelPromotionLifecycleState targetState
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
                sourceState,
                expectedRevision,
                targetState,
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
                        targetState,
                        "proposer",
                        requestedAt.minusSeconds(120),
                        proposalRationale(reference.proposal().proposalId())
                ),
                new ModelPromotionApproval(
                        reference.approval().approvalId(),
                        reference.proposal().proposalId(),
                        ModelPromotionApproval.Outcome.APPROVED,
                        "approver",
                        requestedAt.minusSeconds(60),
                        approvalRationale(reference.proposal().proposalId(), reference.approval().approvalId())
                )
        );
    }

    private ModelPromotionDecisionRationale proposalRationale(String proposalId) {
        return new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.proposalReference(proposalId), RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE)
        );
    }

    private ModelPromotionDecisionRationale approvalRationale(String proposalId, String approvalId) {
        return new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.approvalReference(proposalId, approvalId), RATIONALE,
                ModelPromotionDecisionRationale.sha256(RATIONALE)
        );
    }
}
