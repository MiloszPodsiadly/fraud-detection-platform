package com.frauddetection.alert.security.authorization;

import java.util.Set;

public final class AnalystAuthority {

    public static final String ALERT_READ = "alert:read";
    public static final String ASSISTANT_SUMMARY_READ = "assistant-summary:read";
    public static final String ALERT_DECISION_SUBMIT = "alert:decision:submit";
    public static final String FRAUD_CASE_READ = "fraud-case:read";
    public static final String FRAUD_CASE_UPDATE = "fraud-case:update";
    public static final String TRANSACTION_MONITOR_READ = "transaction-monitor:read";
    public static final String GOVERNANCE_ADVISORY_AUDIT_WRITE = "governance-advisory:audit:write";
    public static final String AUDIT_READ = "audit:read";
    public static final String AUDIT_VERIFY = "audit:verify";
    public static final String AUDIT_EXPORT = "audit:export";

    public static final Set<String> ALL = Set.of(
            ALERT_READ,
            ASSISTANT_SUMMARY_READ,
            ALERT_DECISION_SUBMIT,
            FRAUD_CASE_READ,
            FRAUD_CASE_UPDATE,
            TRANSACTION_MONITOR_READ,
            GOVERNANCE_ADVISORY_AUDIT_WRITE,
            AUDIT_READ,
            AUDIT_VERIFY,
            AUDIT_EXPORT
    );

    private AnalystAuthority() {
    }
}
