package com.frauddetection.alert.regulated.chaos;

public enum Fdp38LiveRuntimeCheckpoint {
    BEFORE_EVIDENCE_PREPARATION(Fdp38PreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST),
    AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE(Fdp38PreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST),
    BEFORE_EVIDENCE_GATED_FINALIZE(Fdp38PreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST);

    private final Fdp38PreconditionSetup preconditionSetup;

    Fdp38LiveRuntimeCheckpoint(Fdp38PreconditionSetup preconditionSetup) {
        this.preconditionSetup = preconditionSetup;
    }

    public Fdp38PreconditionSetup preconditionSetup() {
        return preconditionSetup;
    }
}
