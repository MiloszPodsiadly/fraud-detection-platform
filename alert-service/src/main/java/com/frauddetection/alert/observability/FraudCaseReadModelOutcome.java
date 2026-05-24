package com.frauddetection.alert.observability;

public enum FraudCaseReadModelOutcome {
    AVAILABLE("available"),
    PARTIAL("partial"),
    LEGACY("legacy"),
    TRUNCATED("truncated"),
    EMPTY("empty"),
    NOT_FOUND("not_found"),
    ERROR("error");

    private final String label;

    FraudCaseReadModelOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
