package com.frauddetection.alert.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record OutboxConfirmationResolutionRequest(
        @NotNull
        OutboxConfirmationResolution resolution,

        @JsonProperty("evidence_reference")
        @NotNull
        @Valid
        ResolutionEvidenceReference evidenceReference
) {
}
