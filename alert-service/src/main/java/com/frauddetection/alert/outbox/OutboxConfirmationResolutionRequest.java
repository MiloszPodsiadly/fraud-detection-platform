package com.frauddetection.alert.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OutboxConfirmationResolutionRequest(
        @NotNull
        OutboxConfirmationResolution resolution,

        @NotBlank
        @Size(max = 300)
        String reason,

        @JsonProperty("evidence_reference")
        @NotNull
        @Valid
        ResolutionEvidenceReference evidenceReference
) {
}
