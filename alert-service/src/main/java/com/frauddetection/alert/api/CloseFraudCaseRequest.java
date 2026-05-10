package com.frauddetection.alert.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CloseFraudCaseRequest(
        @NotBlank @Size(max = 500) String closureReason,
        @NotBlank @Size(max = 64) String actorId
) {
}
