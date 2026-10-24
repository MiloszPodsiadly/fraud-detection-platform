package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionPolicyDraftTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void draftHasNoPredeclaredApprovalTimeAndFinalDigestUsesActualAcceptedTime() throws Exception {
        ModelPromotionPolicyDraft draft = draft(1, ModelGovernanceEnvironment.PRODUCTION, BigDecimal.ONE);
        String serialized = mapper.writeValueAsString(draft);
        assertThat(serialized).doesNotContain("approvedAt");
        assertThat(mapper.readValue(serialized, ModelPromotionPolicyDraft.class)).isEqualTo(draft);
        Instant firstAcceptance = Instant.parse("2026-05-20T12:00:00Z");
        Instant delayedAcceptance = Instant.parse("2026-06-15T12:30:00.123Z");
        ModelPromotionPolicy.Content first = draft.approvedContent(firstAcceptance);
        ModelPromotionPolicy.Content delayed = draft.approvedContent(delayedAcceptance);
        assertThat(first.provenance().approvedAt()).isEqualTo(firstAcceptance.toString());
        assertThat(delayed.provenance().approvedAt()).isEqualTo(delayedAcceptance.toString());
        assertThat(first.criteria()).isEqualTo(delayed.criteria());
        assertThat(new ModelPromotionPolicyValidator(mapper).canonicalSha256(first))
                .isNotEqualTo(new ModelPromotionPolicyValidator(mapper).canonicalSha256(delayed));
        assertThat(new ModelPromotionPolicyValidator(mapper).canonicalSha256(delayed))
                .isEqualTo(new ModelPromotionPolicyValidator(mapper).canonicalSha256(
                        mapper.readValue(mapper.writeValueAsString(delayed), ModelPromotionPolicy.Content.class)));
        assertThat(ModelPromotionPolicyDraft.fromApprovedContent(delayed, draft.publisherIdentity(),
                draft.publisherKeyId(), draft.issuerIdentity(), draft.issuerKeyId())).isEqualTo(draft);
        assertThatThrownBy(() -> draft.approvedContent(Instant.parse(draft.effectiveFromInclusive())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyReviewedIdentityAndThresholdChangeProducesAnotherDraftDigest() {
        ModelPromotionPolicyDraft original = draft(1, ModelGovernanceEnvironment.PRODUCTION, BigDecimal.ONE);
        assertThat(draft(1, ModelGovernanceEnvironment.PRODUCTION, BigDecimal.TWO).canonicalSha256(mapper))
                .isNotEqualTo(original.canonicalSha256(mapper));
        assertThat(draft(1, ModelGovernanceEnvironment.TEST, BigDecimal.ONE).canonicalSha256(mapper))
                .isNotEqualTo(original.canonicalSha256(mapper));
        assertThat(draft(2, ModelGovernanceEnvironment.PRODUCTION, BigDecimal.ONE).canonicalSha256(mapper))
                .isNotEqualTo(original.canonicalSha256(mapper));
        assertThat(new ModelPromotionPolicyDraft(ModelPromotionPolicyDraft.VERSION, original.policyId(),
                original.revision(), original.governanceScope(), original.intendedUse(), original.authorIdentity(),
                original.approverIdentity(), original.approvalReference(), original.publisherIdentity(),
                original.publisherKeyId(), original.issuerIdentity(), "another-issuer-key",
                original.effectiveFromInclusive(), original.effectiveToExclusive(), original.criteria())
                .canonicalSha256(mapper)).isNotEqualTo(original.canonicalSha256(mapper));
    }

    private ModelPromotionPolicyDraft draft(long revision, ModelGovernanceEnvironment environment,
            BigDecimal threshold) {
        List<ModelPromotionPolicy.CriterionRule> rules = Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(Collectors.toMap(
                                key -> key, key -> threshold)), 86400, "VERIFIED", criterion.evidenceSource(),
                        criterion.metricSemantics(), criterion.populationDefinition(),
                        criterion.denominatorDefinition(), null))
                .toList();
        return new ModelPromotionPolicyDraft(ModelPromotionPolicyDraft.VERSION, "policy-1", revision,
                new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, environment),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, "author-a", "approver-b", "review-1",
                "publisher-c", "publisher-key", "issuer-d", "issuer-key",
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z", rules);
    }
}
