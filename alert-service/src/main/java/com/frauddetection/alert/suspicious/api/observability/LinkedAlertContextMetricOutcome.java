package com.frauddetection.alert.suspicious.api.observability;

public enum LinkedAlertContextMetricOutcome {
    AVAILABLE("available"),
    NO_LINKED_ALERT("no_linked_alert"),
    LINKED_ALERT_NOT_FOUND("linked_alert_not_found"),
    RELATIONSHIP_MISMATCH("relationship_mismatch"),
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),
    // Client supplied an unsupported selector such as alertId. This is a bounded endpoint outcome, not raw validation detail.
    VALIDATION_ERROR("validation_error"),
    // Source SuspiciousTransaction was not found. This is a bounded endpoint outcome, not a raw identifier.
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
