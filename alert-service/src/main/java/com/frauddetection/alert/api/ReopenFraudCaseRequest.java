package com.frauddetection.alert.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReopenFraudCaseRequest(
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 64) String actorId
) {
}
