package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCaseStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransitionFraudCaseRequest(
        @NotNull FraudCaseStatus targetStatus,
        @Size(max = 64) String actorId
) {
}
