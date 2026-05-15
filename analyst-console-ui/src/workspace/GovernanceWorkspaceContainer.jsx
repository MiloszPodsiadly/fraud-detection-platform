import { GovernanceWorkspacePage } from "../pages/GovernanceWorkspacePage.jsx";

export function GovernanceWorkspaceContainer({
  queueState,
  session,
  canWriteGovernanceAudit,
  onRecordGovernanceAudit
}) {
  return (
    <GovernanceWorkspacePage
      advisoryQueue={queueState.queue}
      advisoryQueueRequest={queueState.request}
      isGovernanceLoading={queueState.isLoading}
      governanceError={queueState.error}
      governanceAuditHistories={queueState.auditHistories}
      session={session}
      canWriteGovernanceAudit={canWriteGovernanceAudit}
      onAdvisoryQueueRequestChange={queueState.setRequest}
      onGovernanceRetry={queueState.refresh}
      onRecordGovernanceAudit={onRecordGovernanceAudit}
      workspaceHeadingProps={workspaceHeadingProps("Operator review queue")}
    />
  );
}

function workspaceHeadingProps(label) {
  return {
    tabIndex: -1,
    "data-workspace-heading": "",
    "data-workspace-label": label
  };
}
