package com.frauddetection.alert.regulated.chaos;

public enum Fdp38LiveRuntimeCheckpoint {
    BEFORE_LEGACY_BUSINESS_MUTATION(Fdp38PreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST),
    AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION(Fdp38PreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST),
    BEFORE_FDP29_LOCAL_FINALIZE(Fdp38PreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST),
    BEFORE_SUCCESS_AUDIT_RETRY(Fdp38PreconditionSetup.SEEDED_DURABLE_PRECONDITION_THEN_RUNTIME_REACHED_CHECKPOINT);

    private final Fdp38PreconditionSetup preconditionSetup;

    Fdp38LiveRuntimeCheckpoint(Fdp38PreconditionSetup preconditionSetup) {
        this.preconditionSetup = preconditionSetup;
    }

    public Fdp38PreconditionSetup preconditionSetup() {
        return preconditionSetup;
    }
}
