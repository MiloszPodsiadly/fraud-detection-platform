package com.frauddetection.alert.governance.audit;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GovernanceAuditRequest(
        @JsonProperty("decision")
        @NotBlank
        String decision,

        @JsonProperty("note")
        @Size(max = 500)
        String note
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported audit request field: " + name);
    }
}
