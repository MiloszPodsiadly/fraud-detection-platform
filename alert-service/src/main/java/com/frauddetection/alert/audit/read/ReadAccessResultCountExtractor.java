package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryAnalyticsResponse;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryListResponse;
import com.frauddetection.alert.governance.audit.GovernanceAuditHistoryResponse;
import org.springframework.stereotype.Component;

@Component
public class ReadAccessResultCountExtractor {

    public int resultCount(Object body, ReadAccessEndpointCategory category) {
        if (body == null) {
            return 0;
        }
        if (body instanceof PagedResponse<?> pagedResponse) {
            return size(pagedResponse.content());
        }
        if (body instanceof GovernanceAuditHistoryResponse historyResponse) {
            return size(historyResponse.auditEvents());
        }
        if (body instanceof GovernanceAdvisoryListResponse listResponse) {
            return size(listResponse.advisoryEvents());
        }
        if (body instanceof GovernanceAdvisoryAnalyticsResponse analyticsResponse && analyticsResponse.totals() != null) {
            return analyticsResponse.totals().advisories();
        }
        return switch (category) {
            case ALERT_DETAIL, FRAUD_CASE_DETAIL, GOVERNANCE_ADVISORY_DETAIL -> 1;
            case SCORED_TRANSACTION_LIST,
                    GOVERNANCE_ADVISORY_LIST,
                    GOVERNANCE_ADVISORY_AUDIT_HISTORY,
                    GOVERNANCE_ADVISORY_ANALYTICS,
                    SYSTEM_TRUST_LEVEL,
                    TRUST_INCIDENT_LIST,
                    PREVIEW_TRUST_INCIDENT_SIGNALS,
                    AUDIT_EVENT_LIST,
                    AUDIT_EVIDENCE_EXPORT,
                    EXTERNAL_AUDIT_INTEGRITY,
                    EXTERNAL_AUDIT_COVERAGE,
                    REGULATED_MUTATION_INSPECTION,
                    REGULATED_MUTATION_RECOVERY_BACKLOG,
                    OUTBOX_RECOVERY_BACKLOG,
                    AUDIT_DEGRADATION_LIST -> 0;
        };
    }

    private int size(java.util.Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }
}
