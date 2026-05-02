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
    public static final String AUDIT_DEGRADATION_RESOLVE = "audit-degradation:resolve";
    public static final String DECISION_OUTBOX_RECONCILE = "decision-outbox:reconcile";
    public static final String REGULATED_MUTATION_RECOVER = "regulated-mutation:recover";
    public static final String OUTBOX_INSPECT = "outbox:inspect";
    public static final String OUTBOX_RECOVER = "outbox:recover";
    public static final String OUTBOX_RESOLVE = "outbox:resolve";
    public static final String TRUST_INCIDENT_READ = "trust-incident:read";
    public static final String TRUST_INCIDENT_ACK = "trust-incident:ack";
    public static final String TRUST_INCIDENT_RESOLVE = "trust-incident:resolve";

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
            AUDIT_EXPORT,
            AUDIT_DEGRADATION_RESOLVE,
            DECISION_OUTBOX_RECONCILE,
            REGULATED_MUTATION_RECOVER,
            OUTBOX_INSPECT,
            OUTBOX_RECOVER,
            OUTBOX_RESOLVE,
            TRUST_INCIDENT_READ,
            TRUST_INCIDENT_ACK,
            TRUST_INCIDENT_RESOLVE
    );

    private AnalystAuthority() {
    }
}
