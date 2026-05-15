import { AnalystWorkspacePage } from "../pages/AnalystWorkspacePage.jsx";

export function AnalystWorkspaceContainer({
  canReadFraudCases,
  workQueueState,
  summaryState,
  onOpenFraudCase
}) {
  return (
    <AnalystWorkspacePage
      canReadFraudCases={canReadFraudCases}
      fraudCaseSummaryError={summaryState.error}
      fraudCaseWorkQueue={workQueueState.queue}
      fraudCaseWorkQueueRequest={workQueueState.committedFilters}
      fraudCaseWorkQueueDraftFilters={workQueueState.draftFilters}
      fraudCaseWorkQueueWarning={workQueueState.warning}
      fraudCaseWorkQueueFilterError={workQueueState.filterError}
      fraudCaseWorkQueueLastRefreshedAt={workQueueState.lastRefreshedAt}
      isFraudCaseWorkQueueLoading={workQueueState.isLoading}
      fraudCaseWorkQueueError={workQueueState.error}
      onFraudCaseSummaryRetry={summaryState.retry}
      onFraudCaseWorkQueueDraftChange={workQueueState.updateDraftFilter}
      onFraudCaseWorkQueueApplyFilters={workQueueState.applyFilters}
      onFraudCaseWorkQueueResetFilters={workQueueState.resetFilters}
      onFraudCaseWorkQueueRetry={workQueueState.refreshFirstSlice}
      onFraudCaseWorkQueueRefreshFirstSlice={workQueueState.refreshFirstSlice}
      onFraudCaseWorkQueueLoadMore={workQueueState.loadMore}
      onOpenFraudCase={onOpenFraudCase}
      workspaceHeadingProps={workspaceHeadingProps("Fraud Case Work Queue")}
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
