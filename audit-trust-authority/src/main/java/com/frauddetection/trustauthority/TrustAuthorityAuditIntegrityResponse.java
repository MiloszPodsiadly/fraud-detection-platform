package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TrustAuthorityAuditIntegrityResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("checked")
        int checked,

        @JsonProperty("latest_chain_position")
        Long latestChainPosition,

        @JsonProperty("latest_event_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String latestEventHash,

        @JsonProperty("reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reasonCode,

        @JsonProperty("violations")
        List<TrustAuthorityAuditIntegrityViolation> violations
) {
    static TrustAuthorityAuditIntegrityResponse unavailable(String reasonCode) {
        return new TrustAuthorityAuditIntegrityResponse("UNAVAILABLE", 0, null, null, reasonCode, List.of());
    }
}
