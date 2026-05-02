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
        List<TrustIncidentResponse> incidents,
        @JsonProperty("operation_status")
        String operationStatus,
        @JsonProperty("transaction_mode")
        String transactionMode,
        @JsonProperty("rollback_applied")
        boolean rollbackApplied,
        @JsonProperty("recovery_required_reason")
        String recoveryRequiredReason
) {
    public TrustIncidentMaterializationResponse(
            String status,
            int signalCount,
            int incidentCount,
            List<TrustIncidentResponse> incidents
    ) {
        this(status, signalCount, incidentCount, signalCount, incidentCount, 0, false, null, incidents, status, null, false, null);
    }

    public TrustIncidentMaterializationResponse withOperationalState(
            String operationStatus,
            String transactionMode,
            boolean rollbackApplied,
            String recoveryRequiredReason
    ) {
        return new TrustIncidentMaterializationResponse(
                status,
                signalCount,
                incidentCount,
                requestedSignalCount,
                materializedCount,
                failedSignalCount,
                partialFailure,
                failureReason,
                incidents,
                operationStatus,
                transactionMode,
                rollbackApplied,
                recoveryRequiredReason
        );
    }
}
