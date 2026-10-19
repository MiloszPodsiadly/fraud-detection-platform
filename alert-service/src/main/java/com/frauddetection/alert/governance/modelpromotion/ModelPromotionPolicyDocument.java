package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Objects;

@Document(collection = ModelPromotionPolicyDocument.COLLECTION)
@Immutable
public record ModelPromotionPolicyDocument(
        @Id String policyKey,
        @Field("institutional_scope") String institutionalScope,
        ModelGovernanceEnvironment environment,
        @Field("intended_use") ModelPromotionIntendedUse intendedUse,
        @Field("policy_id") String policyId,
        @Field("policy_revision") long policyRevision,
        @Field("effective_from") Instant effectiveFrom,
        @Field("effective_to") Instant effectiveTo,
        ModelPromotionPolicy policy
) {

    public static final String COLLECTION = "model_promotion_policies";
    public static final String REVISION_INDEX = "model_promotion_policy_revision_unique_idx";
    public static final String EFFECTIVE_LOOKUP_INDEX = "model_promotion_policy_effective_lookup_idx";

    public ModelPromotionPolicyDocument {
        require(policyKey, "policyKey");
        require(institutionalScope, "institutionalScope");
        Objects.requireNonNull(environment, "environment is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        require(policyId, "policyId");
        if (policyRevision < 1) {
            throw new IllegalArgumentException("policyRevision must be positive");
        }
        Objects.requireNonNull(effectiveFrom, "effectiveFrom is required");
        Objects.requireNonNull(effectiveTo, "effectiveTo is required");
        Objects.requireNonNull(policy, "policy is required");
        ModelPromotionPolicy.Content content = policy.content();
        ModelGovernanceScope policyScope = content.governanceScope();
        if (!institutionalScope.equals(policyScope.institutionalScope())
                || environment != policyScope.environment()
                || intendedUse != content.intendedUse()
                || !policyId.equals(content.policyId())
                || policyRevision != content.revision()
                || !effectiveFrom.equals(timestamp(content.effectiveFromInclusive()))
                || !effectiveTo.equals(timestamp(content.effectiveToExclusive()))
                || !policyKey.equals(keyFor(content))) {
            throw new IllegalArgumentException("policy document metadata does not match canonical policy content");
        }
    }

    static ModelPromotionPolicyDocument from(ModelPromotionPolicy policy) {
        Objects.requireNonNull(policy, "policy is required");
        ModelPromotionPolicy.Content content = policy.content();
        ModelGovernanceScope scope = content.governanceScope();
        return new ModelPromotionPolicyDocument(
                keyFor(content),
                scope.institutionalScope(),
                scope.environment(),
                content.intendedUse(),
                content.policyId(),
                content.revision(),
                timestamp(content.effectiveFromInclusive()),
                timestamp(content.effectiveToExclusive()),
                policy
        );
    }

    private static String keyFor(ModelPromotionPolicy.Content content) {
        ModelGovernanceScope scope = content.governanceScope();
        return String.join(
                ":",
                "model-promotion-policy-v1",
                scope.institutionalScope(),
                scope.environment().name(),
                content.intendedUse().name(),
                content.policyId(),
                Long.toString(content.revision())
        );
    }

    private static Instant timestamp(String value) {
        return GovernanceTimestampContract.parse(value, "policy timestamp");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
