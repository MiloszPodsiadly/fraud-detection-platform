package com.frauddetection.alert.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignFraudCaseRequest(
        @NotBlank @Size(max = 64) String assignedInvestigatorId,
        @NotBlank @Size(max = 64) String actorId
) {
}
