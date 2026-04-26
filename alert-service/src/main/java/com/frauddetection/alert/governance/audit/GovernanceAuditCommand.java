package com.frauddetection.alert.governance.audit;

record GovernanceAuditCommand(
        GovernanceAuditDecision decision,
        String note
) {
}
