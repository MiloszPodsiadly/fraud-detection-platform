package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExternalAuditAnchorMissingRange(
        @JsonProperty("from_chain_position")
        long fromChainPosition,

        @JsonProperty("to_chain_position")
        long toChainPosition
) {
}
