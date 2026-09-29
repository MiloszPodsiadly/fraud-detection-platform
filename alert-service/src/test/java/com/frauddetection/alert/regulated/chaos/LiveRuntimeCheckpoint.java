package com.frauddetection.alert.regulated.chaos;

public enum LiveRuntimeCheckpoint {
    BEFORE_EVIDENCE_PREPARATION(LiveCheckpointPreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST),
    AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE(LiveCheckpointPreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST),
    BEFORE_EVIDENCE_GATED_FINALIZE(LiveCheckpointPreconditionSetup.LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST);

    private final LiveCheckpointPreconditionSetup preconditionSetup;

    LiveRuntimeCheckpoint(LiveCheckpointPreconditionSetup preconditionSetup) {
        this.preconditionSetup = preconditionSetup;
    }

    public LiveCheckpointPreconditionSetup preconditionSetup() {
        return preconditionSetup;
    }
}
