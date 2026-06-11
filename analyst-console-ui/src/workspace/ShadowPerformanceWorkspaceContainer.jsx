import { ShadowPerformanceDashboardPage } from "../pages/ShadowPerformanceDashboardPage.jsx";

export function ShadowPerformanceWorkspaceContainer({
  headingLabel = "Shadow Performance Summary",
  summaryState,
  canReadShadowPerformance
}) {
  return (
    <ShadowPerformanceDashboardPage
      summary={summaryState.summary}
      isLoading={summaryState.isLoading}
      error={summaryState.error}
      canReadShadowPerformance={canReadShadowPerformance}
      onRetry={summaryState.refresh}
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
