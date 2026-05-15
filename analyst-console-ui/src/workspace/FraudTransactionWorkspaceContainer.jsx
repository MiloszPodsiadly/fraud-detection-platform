import { FraudTransactionWorkspacePage } from "../pages/FraudTransactionWorkspacePage.jsx";

export function FraudTransactionWorkspaceContainer({
  headingLabel = "Alert review queue",
  alertQueueState,
  onRetryWorkspace,
  onPageChange,
  onPageSizeChange,
  onOpenAlert
}) {
  return (
    <FraudTransactionWorkspacePage
      alertPage={alertQueueState.page}
      isLoading={alertQueueState.isLoading}
      error={alertQueueState.error}
      onRetry={onRetryWorkspace}
      onAlertPageChange={onPageChange}
      onAlertPageSizeChange={onPageSizeChange}
      onOpenAlert={onOpenAlert}
      workspaceHeadingProps={workspaceHeadingProps(headingLabel)}
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
