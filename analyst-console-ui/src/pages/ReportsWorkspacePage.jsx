import { GovernanceAnalyticsPanel } from "../components/GovernanceAnalyticsPanel.jsx";

export function ReportsWorkspacePage({
  governanceAnalytics,
  analyticsWindowDays,
  isAnalyticsLoading,
  analyticsError,
  onAnalyticsWindowDaysChange,
  onAnalyticsRetry
}) {
  return (
    <GovernanceAnalyticsPanel
      analytics={governanceAnalytics}
      windowDays={analyticsWindowDays}
      isLoading={isAnalyticsLoading}
      error={analyticsError}
      onWindowDaysChange={onAnalyticsWindowDaysChange}
      onRetry={onAnalyticsRetry}
    />
  );
}
