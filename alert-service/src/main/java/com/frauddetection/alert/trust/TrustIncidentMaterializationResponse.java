package com.frauddetection.alert.trust;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TrustIncidentMaterializationResponse(
        String status,
        @JsonProperty("signal_count")
        int signalCount,
        @JsonProperty("incident_count")
        int incidentCount,
        List<TrustIncidentResponse> incidents
) {
}
