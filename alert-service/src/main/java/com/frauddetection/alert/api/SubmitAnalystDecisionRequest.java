package com.frauddetection.alert.api;

import com.frauddetection.common.events.enums.AnalystDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record SubmitAnalystDecisionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String analystId,
        @NotNull AnalystDecision decision,
        @NotBlank @Size(max = 500) String decisionReason,
        @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String> tags,
        @Size(max = 50) Map<String, Object> decisionMetadata
) {
}
