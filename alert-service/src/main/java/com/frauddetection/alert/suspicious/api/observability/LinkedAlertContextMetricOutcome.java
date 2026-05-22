package com.frauddetection.alert.suspicious.api.observability;

public enum LinkedAlertContextMetricOutcome {
    AVAILABLE("available"),
    NO_LINKED_ALERT("no_linked_alert"),
    LINKED_ALERT_NOT_FOUND("linked_alert_not_found"),
    RELATIONSHIP_MISMATCH("relationship_mismatch"),
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),
    VALIDATION_ERROR("validation_error"),
    SUSPICIOUS_TRANSACTION_NOT_FOUND("suspicious_transaction_not_found"),
    ERROR("error");

    private final String label;

    LinkedAlertContextMetricOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
