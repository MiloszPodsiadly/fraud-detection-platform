import { useCallback } from "react";

export function useGovernanceAuditWorkflow({
  apiClient,
  canWriteGovernanceAudit,
  governanceQueueState,
  governanceAnalyticsState
}) {
  return useCallback(async (eventId, audit) => {
    if (!apiClient) {
      return auditFailure("runtime-not-ready", "Workspace runtime is not ready.");
    }
    if (canWriteGovernanceAudit !== true) {
      return auditFailure("write-authority-unavailable", "Governance audit write authority is not available.");
    }

    try {
      await apiClient.recordGovernanceAdvisoryAudit(eventId, audit);
    } catch (apiError) {
      return auditFailure("record-failed", messageFor(apiError, "Unable to record governance advisory review."), apiError);
    }

    let nextHistory;
    try {
      nextHistory = await apiClient.getGovernanceAdvisoryAudit(eventId);
    } catch (apiError) {
      return auditFailure("history-refresh-failed", messageFor(apiError, "Review was recorded, but audit history could not be refreshed."), apiError);
    }

    governanceQueueState.setAuditHistories((current) => ({
      ...current,
      [eventId]: nextHistory
    }));
    const latestDecision = nextHistory.audit_events?.[0]?.decision || "OPEN";
    governanceQueueState.setQueue((current) => ({
      ...current,
      advisory_events: current.advisory_events
        .map((event) => (event.event_id === eventId ? { ...event, lifecycle_status: latestDecision } : event))
        .filter((event) => governanceQueueState.request.lifecycleStatus === "ALL" || event.lifecycle_status === governanceQueueState.request.lifecycleStatus)
    }));

    try {
      await governanceAnalyticsState.refresh();
    } catch {
      // Analytics refresh is a secondary read; do not undo the recorded review UI state.
    }
    return { ok: true };
  }, [apiClient, canWriteGovernanceAudit, governanceAnalyticsState, governanceQueueState]);
}

function auditFailure(reason, message, error = null) {
  return { ok: false, reason, message, error };
}

function messageFor(error, fallback) {
  return error?.message || fallback;
}
