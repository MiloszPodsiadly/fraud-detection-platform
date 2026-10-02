package com.frauddetection.alert.regulated;

public class ConflictingResourceMutationException extends RuntimeException {

    public ConflictingResourceMutationException() {
        super("A regulated mutation already exists for this resource and action.");
    }
}
