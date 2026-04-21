package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCaseStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateFraudCaseRequest(
        @NotNull FraudCaseStatus status,
        @NotBlank @Size(max = 64) String analystId,
        @NotBlank @Size(max = 500) String decisionReason,
        @Size(max = 20) List<@Size(max = 64) String> tags
) {
}
