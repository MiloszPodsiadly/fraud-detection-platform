package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.common.events.enums.RiskLevel;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateFraudCaseRequest(
        @NotEmpty List<@Size(min = 1, max = 128) String> alertIds,
        FraudCasePriority priority,
        RiskLevel riskLevel,
        @Size(max = 500) String reason,
        @Size(max = 64) String actorId
) {
}
