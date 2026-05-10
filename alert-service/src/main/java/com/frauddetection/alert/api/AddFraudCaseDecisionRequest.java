package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCaseDecisionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddFraudCaseDecisionRequest(
        @NotNull FraudCaseDecisionType decisionType,
        @NotBlank @Size(max = 2000) String summary,
        @NotBlank @Size(max = 64) String actorId
) {
}
