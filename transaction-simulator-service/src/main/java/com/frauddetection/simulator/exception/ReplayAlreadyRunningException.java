package com.frauddetection.simulator.exception;

public class ReplayAlreadyRunningException extends RuntimeException {

    public ReplayAlreadyRunningException(String message) {
        super(message);
    }
}
