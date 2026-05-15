import { useGovernanceAnalytics } from "./useGovernanceAnalytics.js";
import { useGovernanceAuditWorkflow } from "./useGovernanceAuditWorkflow.js";
import { useGovernanceQueue } from "./useGovernanceQueue.js";

export function useGovernanceWorkspaceRuntime({
  workspacePage,
  sharedWorkspaceReadsEnabled,
  canReadGovernanceAdvisories,
  canWriteGovernanceAudit,
  apiClient
}) {
  const queueState = useGovernanceQueue({
    enabled: workspacePage === "compliance" && sharedWorkspaceReadsEnabled && canReadGovernanceAdvisories === true
  });
  const analyticsState = useGovernanceAnalytics({
    enabled: workspacePage === "reports" && sharedWorkspaceReadsEnabled && canReadGovernanceAdvisories === true
  });
  const recordGovernanceAudit = useGovernanceAuditWorkflow({
    apiClient,
    canWriteGovernanceAudit,
    governanceQueueState: queueState,
    governanceAnalyticsState: analyticsState
  });

  return {
    queueState,
    analyticsState,
    recordGovernanceAudit
  };
}
