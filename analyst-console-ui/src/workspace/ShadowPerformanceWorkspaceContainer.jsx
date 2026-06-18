import { ShadowPerformanceDashboardPage } from "../pages/ShadowPerformanceDashboardPage.jsx";

export function ShadowPerformanceWorkspaceContainer({
  headingLabel = "Shadow Performance Summary",
  summaryState,
  promotionReadinessState = { report: null, isLoading: false, error: null },
  canReadShadowPerformance,
  canReadPromotionReadiness
}) {
  return (
    <ShadowPerformanceDashboardPage
      summary={summaryState.summary}
      isLoading={summaryState.isLoading}
      error={summaryState.error}
      promotionReadinessReport={promotionReadinessState.report}
      promotionReadinessIsLoading={promotionReadinessState.isLoading}
      promotionReadinessError={promotionReadinessState.error}
      canReadShadowPerformance={canReadShadowPerformance}
      canReadPromotionReadiness={canReadPromotionReadiness}
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
