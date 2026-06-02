package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse;
import com.frauddetection.alert.api.FraudCaseEvidenceTimelineResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryAnalyticsResponse;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryListResponse;
import com.frauddetection.alert.governance.audit.GovernanceAuditHistoryResponse;
import com.frauddetection.alert.suspicious.api.AlertLinkedContextResponse;
import com.frauddetection.alert.suspicious.api.LinkedAlertContextState;
import com.frauddetection.alert.suspicious.api.SuspiciousTransactionSliceResponse;
import com.frauddetection.alert.suspicious.api.SuspiciousTransactionSummaryResponse;
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
        if (body instanceof FraudCaseWorkQueueSliceResponse sliceResponse) {
            return size(sliceResponse.content());
        }
        if (body instanceof FraudCaseEvidenceSummaryResponse response) {
            return Math.max(0, response.evidenceItemCount());
        }
        if (body instanceof FraudCaseEvidenceTimelineResponse response) {
            return size(response.events());
        }
        if (body instanceof SuspiciousTransactionSliceResponse sliceResponse) {
            return size(sliceResponse.content());
        }
        if (body instanceof SuspiciousTransactionSummaryResponse) {
            return 1;
        }
        if (body instanceof EngineIntelligenceReadModel response) {
            return response.available() ? 1 : 0;
        }
        if (body instanceof AlertLinkedContextResponse response) {
            return response.state() == LinkedAlertContextState.LINKED_ALERT_AVAILABLE ? 1 : 0;
        }
        if (body instanceof FraudCaseWorkQueueSummaryResponse) {
            return 1;
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
            case ALERT_DETAIL, FRAUD_CASE_DETAIL, FRAUD_CASE_EVIDENCE_SUMMARY, GOVERNANCE_ADVISORY_DETAIL -> 1;
            case SCORED_TRANSACTION_SEARCH,
                    ENGINE_INTELLIGENCE_READ,
                    SUSPICIOUS_TRANSACTION_SEARCH,
                    SUSPICIOUS_TRANSACTION_READ,
                    SUSPICIOUS_TRANSACTION_LINKED_ALERT_CONTEXT,
                    SUSPICIOUS_TRANSACTION_SUMMARY,
                    FRAUD_CASE_EVIDENCE_TIMELINE,
                    FRAUD_CASE_WORK_QUEUE,
                    FRAUD_CASE_WORK_QUEUE_SUMMARY,
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
