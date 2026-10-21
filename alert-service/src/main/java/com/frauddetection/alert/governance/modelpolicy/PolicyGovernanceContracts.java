package com.frauddetection.alert.governance.modelpolicy;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceScope;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionIntendedUse;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;

import java.util.List;

public final class PolicyGovernanceContracts {
    private PolicyGovernanceContracts() {
    }

    public record DraftInput(
            @NotBlank String policyId,
            long revision,
            @NotNull ModelGovernanceScope governanceScope,
            @NotNull ModelPromotionIntendedUse intendedUse,
            @NotBlank @Pattern(regexp = "^oidc:[0-9a-f]{64}$") String intendedApproverActorId,
            @NotBlank String approvalReference,
            @NotBlank String effectiveFromInclusive,
            @NotBlank String effectiveToExclusive,
            @NotNull List<ModelPromotionPolicy.CriterionRule> criteria
    ) {
        @JsonAnySetter
        public void rejectUnknownAuthorityFields(String field, Object ignored) {
            throw new IllegalArgumentException("Unknown policy draft field: " + field);
        }
    }

    public record DraftCommand(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String operationId,
            @NotNull @Valid DraftInput draft) {
    }

    public record UpdateDraftCommand(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String operationId,
            long expectedVersion, @NotNull @Valid DraftInput draft) {
    }

    public record TransitionCommand(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String operationId,
            long expectedVersion) {
    }

    public record ReviewCommand(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String operationId,
            long expectedVersion, @NotNull ReviewDecision decision,
            @NotBlank String approvalEvidenceReference) {
    }

    public enum ReviewDecision { APPROVE, REJECT }
}
