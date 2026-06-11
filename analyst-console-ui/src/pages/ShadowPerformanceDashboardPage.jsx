import { ShadowPerformanceDashboard } from "../components/ShadowPerformanceDashboard.jsx";

export function ShadowPerformanceDashboardPage({
  summary,
  isLoading,
  error,
  canReadShadowPerformance,
  onRetry,
  workspaceHeadingProps = {}
}) {
  return (
    <ShadowPerformanceDashboard
      summary={summary}
      isLoading={isLoading}
      error={error}
      canReadShadowPerformance={canReadShadowPerformance}
      onRetry={onRetry}
      headingProps={workspaceHeadingProps}
    />
  );
}
