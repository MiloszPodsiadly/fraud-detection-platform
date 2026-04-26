package com.frauddetection.alert.governance.audit;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum GovernanceAuditDecision {
    ACKNOWLEDGED,
    NEEDS_FOLLOW_UP,
    DISMISSED_AS_NOISE;

    public static GovernanceAuditDecision parse(String value) {
        if (value == null) {
            throw new InvalidGovernanceAuditDecisionException();
        }
        try {
            return GovernanceAuditDecision.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new InvalidGovernanceAuditDecisionException();
        }
    }

    public static Set<String> allowedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
