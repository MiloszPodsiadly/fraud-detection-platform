package com.frauddetection.alert.fraudcase;

public class UnsupportedFraudCaseLifecycleReplaySnapshotException extends RuntimeException {

    public UnsupportedFraudCaseLifecycleReplaySnapshotException() {
        super("Unsupported fraud case lifecycle replay snapshot response type.");
    }
}
