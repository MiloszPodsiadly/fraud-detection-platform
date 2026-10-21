package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable reviewable policy identity, before any approval time or issuer signature exists. */
public record ModelPromotionPolicyDraft(
        String draftVersion,
        String policyId,
        long revision,
        ModelGovernanceScope governanceScope,
        ModelPromotionIntendedUse intendedUse,
        String authorIdentity,
        String approverIdentity,
        String approvalReference,
        String publisherIdentity,
        String publisherKeyId,
        String issuerIdentity,
        String issuerKeyId,
        String effectiveFromInclusive,
        String effectiveToExclusive,
        List<ModelPromotionPolicy.CriterionRule> criteria
) {

    public static final String VERSION = "model-promotion-policy-draft-v1";

    public ModelPromotionPolicyDraft {
        if (!VERSION.equals(draftVersion) || revision < 1) {
            throw new IllegalArgumentException("Unsupported policy draft version or revision");
        }
        identifier(policyId, "policyId");
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        identifier(authorIdentity, "authorIdentity");
        identifier(approverIdentity, "approverIdentity");
        identifier(approvalReference, "approvalReference");
        identifier(publisherIdentity, "publisherIdentity");
        identifier(publisherKeyId, "publisherKeyId");
        identifier(issuerIdentity, "issuerIdentity");
        identifier(issuerKeyId, "issuerKeyId");
        if (authorIdentity.equals(approverIdentity) || authorIdentity.equals(issuerIdentity)
                || approverIdentity.equals(issuerIdentity)) {
            throw new IllegalArgumentException("Policy author, approver and issuer must be distinct actors");
        }
        Instant from = canonicalTime(effectiveFromInclusive, "effectiveFromInclusive");
        Instant to = canonicalTime(effectiveToExclusive, "effectiveToExclusive");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Policy draft effective interval is invalid");
        }
        criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria are required"));
    }

    public String canonicalSha256(ObjectMapper mapper) {
        return GovernanceCanonicalJson.sha256(Objects.requireNonNull(mapper), this);
    }

    public ModelPromotionPolicy.Content approvedContent(Instant acceptedAt) {
        Instant effectiveFrom = canonicalTime(effectiveFromInclusive, "effectiveFromInclusive");
        if (acceptedAt == null || !acceptedAt.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("Approval must be accepted before policy effectiveness");
        }
        return new ModelPromotionPolicy.Content(ModelPromotionPolicy.CURRENT_VERSION, policyId, revision,
                governanceScope, intendedUse,
                new ModelPromotionPolicy.PolicyProvenance(
                        ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        authorIdentity, approverIdentity, acceptedAt.toString(), approvalReference),
                effectiveFromInclusive, effectiveToExclusive, criteria);
    }

    public static ModelPromotionPolicyDraft fromApprovedContent(ModelPromotionPolicy.Content content,
            String publisherIdentity, String publisherKeyId, String issuerIdentity, String issuerKeyId) {
        Objects.requireNonNull(content, "content is required");
        ModelPromotionPolicy.PolicyProvenance provenance = content.provenance();
        if (provenance.authority() != ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED) {
            throw new IllegalArgumentException("Only institutional content can derive a policy draft");
        }
        return new ModelPromotionPolicyDraft(VERSION, content.policyId(), content.revision(),
                content.governanceScope(), content.intendedUse(), provenance.authorIdentity(),
                provenance.approvingAuthority(), provenance.approvalReference(), publisherIdentity,
                publisherKeyId, issuerIdentity, issuerKeyId, content.effectiveFromInclusive(),
                content.effectiveToExclusive(), content.criteria());
    }

    private static Instant canonicalTime(String value, String field) {
        Instant parsed = GovernanceTimestampContract.parse(value, field);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException(field + " must be canonical UTC");
        }
        return parsed;
    }

    private static void identifier(String value, String field) {
        if (value == null || !value.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
