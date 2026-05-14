import { useCallback } from "react";

export function useGovernanceAuditWorkflow({
  apiClient,
  canWriteGovernanceAudit,
  governanceQueueState,
  governanceAnalyticsState
}) {
  return useCallback(async (eventId, audit) => {
    if (!apiClient) {
      throw new Error("Workspace runtime is not ready.");
    }
    if (canWriteGovernanceAudit !== true) {
      throw new Error("Governance audit write authority is not available.");
    }

    await apiClient.recordGovernanceAdvisoryAudit(eventId, audit);
    const nextHistory = await apiClient.getGovernanceAdvisoryAudit(eventId);
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
    governanceAnalyticsState.refresh();
  }, [apiClient, canWriteGovernanceAudit, governanceAnalyticsState, governanceQueueState]);
}
