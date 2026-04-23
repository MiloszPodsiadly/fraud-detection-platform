package com.frauddetection.alert.security.authorization;

import java.util.Set;

public enum AnalystRole {
    READ_ONLY_ANALYST(Set.of(
            AnalystAuthority.ALERT_READ,
            AnalystAuthority.ASSISTANT_SUMMARY_READ,
            AnalystAuthority.FRAUD_CASE_READ,
            AnalystAuthority.TRANSACTION_MONITOR_READ
    )),
    ANALYST(Set.of(
            AnalystAuthority.ALERT_READ,
            AnalystAuthority.ASSISTANT_SUMMARY_READ,
            AnalystAuthority.ALERT_DECISION_SUBMIT,
            AnalystAuthority.FRAUD_CASE_READ,
            AnalystAuthority.TRANSACTION_MONITOR_READ
    )),
    REVIEWER(Set.of(
            AnalystAuthority.ALERT_READ,
            AnalystAuthority.ASSISTANT_SUMMARY_READ,
            AnalystAuthority.ALERT_DECISION_SUBMIT,
            AnalystAuthority.FRAUD_CASE_READ,
            AnalystAuthority.FRAUD_CASE_UPDATE,
            AnalystAuthority.TRANSACTION_MONITOR_READ
    )),
    FRAUD_OPS_ADMIN(AnalystAuthority.ALL);

    private final Set<String> authorities;

    AnalystRole(Set<String> authorities) {
        this.authorities = Set.copyOf(authorities);
    }

    public Set<String> authorities() {
        return authorities;
    }
}
