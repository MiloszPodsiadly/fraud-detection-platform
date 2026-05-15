import { GovernanceReviewQueue } from "../components/GovernanceReviewQueue.jsx";

export function GovernanceWorkspacePage({
  advisoryQueue,
  advisoryQueueRequest,
  isGovernanceLoading,
  governanceError,
  governanceAuditHistories,
  session,
  canWriteGovernanceAudit,
  onAdvisoryQueueRequestChange,
  onGovernanceRetry,
  onRecordGovernanceAudit
}) {
  return (
    <GovernanceReviewQueue
      advisoryQueue={advisoryQueue}
      filters={advisoryQueueRequest}
      isLoading={isGovernanceLoading}
      error={governanceError}
      auditHistories={governanceAuditHistories}
      session={session}
      canRecordAudit={canWriteGovernanceAudit === true}
      onFiltersChange={onAdvisoryQueueRequestChange}
      onRetry={onGovernanceRetry}
      onRecordAudit={onRecordGovernanceAudit}
    />
  );
}
