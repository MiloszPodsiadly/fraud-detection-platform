import { ReportsWorkspacePage } from "../pages/ReportsWorkspacePage.jsx";

export function ReportsWorkspaceContainer({ headingLabel = "Review visibility", analyticsState }) {
  return (
    <ReportsWorkspacePage
      governanceAnalytics={analyticsState.analytics}
      analyticsWindowDays={analyticsState.windowDays}
      isAnalyticsLoading={analyticsState.isLoading}
      analyticsError={analyticsState.error}
      onAnalyticsWindowDaysChange={analyticsState.setWindowDays}
      onAnalyticsRetry={analyticsState.refresh}
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
