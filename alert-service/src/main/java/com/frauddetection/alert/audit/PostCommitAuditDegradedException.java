package com.frauddetection.alert.audit;

public class PostCommitAuditDegradedException extends RuntimeException {

    private final Object result;

    public PostCommitAuditDegradedException(Object result, RuntimeException cause) {
        super("Business mutation committed but audit evidence is incomplete.", cause);
        this.result = result;
    }

    @SuppressWarnings("unchecked")
    public <T> T result() {
        return (T) result;
    }
}
