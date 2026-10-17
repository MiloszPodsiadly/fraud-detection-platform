package com.frauddetection.alert.governance.modelpromotion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record ModelPromotionPolicy(Content content, String canonicalSha256) {

    public static final String CURRENT_VERSION = "model-promotion-policy-v1";
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public ModelPromotionPolicy {
        Objects.requireNonNull(content, "content is required");
        sha256(canonicalSha256, "canonicalSha256");
    }

    public record Content(
            String policyVersion,
            String policyId,
            long revision,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            PolicyProvenance provenance,
            String effectiveFromInclusive,
            String effectiveToExclusive,
            List<CriterionRule> criteria
    ) {
        public Content {
            if (!CURRENT_VERSION.equals(policyVersion)) {
                throw new IllegalArgumentException("policyVersion is unsupported");
            }
            identifier(policyId, "policyId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            Objects.requireNonNull(governanceScope, "governanceScope is required");
            Objects.requireNonNull(intendedUse, "intendedUse is required");
            Objects.requireNonNull(provenance, "provenance is required");
            required(effectiveFromInclusive, "effectiveFromInclusive");
            required(effectiveToExclusive, "effectiveToExclusive");
            criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria are required"));
        }
    }

    public record PolicyProvenance(
            PolicyAuthority authority,
            String authorIdentity,
            String approvingAuthority,
            String approvedAt,
            String approvalReference
    ) {
        public PolicyProvenance {
            Objects.requireNonNull(authority, "authority is required");
            identifier(authorIdentity, "authorIdentity");
            identifier(approvingAuthority, "approvingAuthority");
            required(approvedAt, "approvedAt");
            identifier(approvalReference, "approvalReference");
        }
    }

    public record CriterionRule(
            ModelPromotionCriterion criterion,
            CriterionApplicability applicability,
            Map<String, BigDecimal> thresholds,
            long maximumEvidenceAgeSeconds,
            String requiredVerificationLevel,
            String evidenceSource,
            String metricSemantics,
            String populationDefinition,
            String denominatorDefinition
    ) {
        public CriterionRule {
            Objects.requireNonNull(criterion, "criterion is required");
            Objects.requireNonNull(applicability, "applicability is required");
            thresholds = Map.copyOf(Objects.requireNonNull(thresholds, "thresholds are required"));
            if (maximumEvidenceAgeSeconds <= 0) {
                throw new IllegalArgumentException("maximumEvidenceAgeSeconds must be positive");
            }
            required(requiredVerificationLevel, "requiredVerificationLevel");
            if (!criterion.evidenceSource().equals(evidenceSource)
                    || !criterion.metricSemantics().equals(metricSemantics)
                    || !criterion.populationDefinition().equals(populationDefinition)
                    || !criterion.denominatorDefinition().equals(denominatorDefinition)) {
                throw new IllegalArgumentException("criterion semantics do not match the canonical contract");
            }
            if (applicability == CriterionApplicability.MANDATORY
                    && !thresholds.keySet().equals(criterion.requiredThresholdKeys())) {
                throw new IllegalArgumentException("criterion thresholds do not match canonical semantics");
            }
            if (applicability == CriterionApplicability.NOT_APPLICABLE_AUTHORIZED && !thresholds.isEmpty()) {
                throw new IllegalArgumentException("not-applicable criterion must not define thresholds");
            }
            thresholds.forEach((name, value) -> {
                required(name, "threshold name");
                Objects.requireNonNull(value, "threshold value is required");
            });
        }
    }

    public enum PolicyAuthority {
        INSTITUTIONAL_APPROVED,
        NON_INSTITUTIONAL_TEST
    }

    public enum CriterionApplicability {
        MANDATORY,
        NOT_APPLICABLE_AUTHORIZED
    }

    private static void identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void sha256(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
