package com.frauddetection.alert.trust;

import jakarta.validation.constraints.Size;

public record TrustIncidentAcknowledgementRequest(
        @Size(max = 300)
        String reason
) {
}
