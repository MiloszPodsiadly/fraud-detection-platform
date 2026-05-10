package com.frauddetection.alert.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddFraudCaseNoteRequest(
        @NotBlank @Size(max = 2000) String body,
        boolean internalOnly,
        @NotBlank @Size(max = 64) String actorId
) {
}
