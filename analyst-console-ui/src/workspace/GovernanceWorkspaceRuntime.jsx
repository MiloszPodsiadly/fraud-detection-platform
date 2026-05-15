import { GovernanceWorkspaceContainer } from "./GovernanceWorkspaceContainer.jsx";
import { useGovernanceAuditWorkflow } from "./useGovernanceAuditWorkflow.js";
import { useGovernanceQueue } from "./useGovernanceQueue.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

// Compliance runtime owns advisory queue freshness. Reports analytics is owned by
// ReportsWorkspaceRuntime and refreshes when Reports is active or explicitly retried.
const NO_ANALYTICS_REFRESH = {
  refresh: async () => {}
};

export function GovernanceWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  children
}) {
  const {
    session,
    apiClient,
    canReadGovernanceAdvisories,
    canWriteGovernanceAudit
  } = useWorkspaceRuntime();
  const queueState = useGovernanceQueue({
    enabled: sharedWorkspaceReadsEnabled && canReadGovernanceAdvisories === true
  });
  const recordGovernanceAudit = useGovernanceAuditWorkflow({
    apiClient,
    canWriteGovernanceAudit,
    governanceQueueState: queueState,
    governanceAnalyticsState: NO_ANALYTICS_REFRESH
  });

  function refreshWorkspace() {
    queueState.refresh();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <GovernanceWorkspaceContainer
        headingLabel={route.heading.label}
        queueState={queueState}
        session={session}
        canWriteGovernanceAudit={canWriteGovernanceAudit}
        onRecordGovernanceAudit={recordGovernanceAudit}
      />
    ),
    navigationState: {
      advisoryQueue: queueState.queue
    },
    error: queueState.error,
    refreshWorkspace
  }));
}
