package com.frauddetection.alert.trust;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TrustIncidentResolutionRequest(
        @NotBlank
        @Size(max = 500)
        String reason,

        @JsonProperty("resolution_evidence")
        @NotNull
        @Valid
        ResolutionEvidenceReference resolutionEvidence,

        @JsonProperty("false_positive")
        boolean falsePositive
) {
}
