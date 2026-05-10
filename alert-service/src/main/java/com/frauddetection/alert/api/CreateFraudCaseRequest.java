package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.common.events.enums.RiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateFraudCaseRequest(
        @NotEmpty List<@NotBlank @Size(max = 128) String> alertIds,
        @NotNull FraudCasePriority priority,
        @NotNull RiskLevel riskLevel,
        @Size(max = 500) String reason,
        @NotBlank @Size(max = 64) String actorId
) {
}
