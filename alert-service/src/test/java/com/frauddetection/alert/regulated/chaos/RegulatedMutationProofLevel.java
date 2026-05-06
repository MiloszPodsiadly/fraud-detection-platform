package com.frauddetection.alert.regulated.chaos;

public enum RegulatedMutationProofLevel {
    REAL_ALERT_SERVICE_KILL,
    REAL_ALERT_SERVICE_RESTART_API_PROOF,
    DUMMY_CONTAINER_DURABLE_STATE_PROOF,
    MODELED_DURABLE_STATE_PROOF,
    API_PERSISTED_STATE_PROOF
}
