package com.frauddetection.alert.system;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SystemTrustLevelResponse(
        @JsonProperty("guarantee_level")
        String guaranteeLevel,

        @JsonProperty("publication_enabled")
        boolean publicationEnabled,

        @JsonProperty("publication_required")
        boolean publicationRequired,

        @JsonProperty("fail_closed")
        boolean failClosed,

        @JsonProperty("external_anchor_strength")
        String externalAnchorStrength
) {
}
