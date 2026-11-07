package com.frauddetection.alert.persistence;

public final class FeedbackOccurrenceFenceException extends RuntimeException {

    private final Reason reason;

    public FeedbackOccurrenceFenceException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        OCCURRENCE_CHANGED,
        TRANSACTION_REQUIRED,
        FENCE_UNAVAILABLE
    }
}
