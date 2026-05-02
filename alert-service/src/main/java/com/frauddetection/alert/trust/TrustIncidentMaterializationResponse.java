package com.frauddetection.alert.trust;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TrustIncidentMaterializationResponse(
        String status,
        @JsonProperty("signal_count")
        int signalCount,
        @JsonProperty("incident_count")
        int incidentCount,
        @JsonProperty("requested_signal_count")
        int requestedSignalCount,
        @JsonProperty("materialized_count")
        int materializedCount,
        @JsonProperty("failed_signal_count")
        int failedSignalCount,
        @JsonProperty("partial_failure")
        boolean partialFailure,
        @JsonProperty("failure_reason")
        String failureReason,
        List<TrustIncidentResponse> incidents
) {
    public TrustIncidentMaterializationResponse(
            String status,
            int signalCount,
            int incidentCount,
            List<TrustIncidentResponse> incidents
    ) {
        this(status, signalCount, incidentCount, signalCount, incidentCount, 0, false, null, incidents);
    }
}
