package com.frauddetection.alert.regulated;

public class RegulatedMutationAlertProjectionException extends RuntimeException {

    public RegulatedMutationAlertProjectionException(String commandId, String message) {
        super(message + " commandId=" + commandId);
    }

    public RegulatedMutationAlertProjectionException(String commandId, String message, Throwable cause) {
        super(message + " commandId=" + commandId, cause);
    }
}
