import { ShadowPerformanceDashboard } from "../components/ShadowPerformanceDashboard.jsx";
import { PromotionReviewReadinessPanel } from "../components/PromotionReviewReadinessPanel.jsx";

export function ShadowPerformanceDashboardPage({
  summary,
  isLoading,
  error,
  promotionReadinessReport,
  promotionReadinessIsLoading,
  promotionReadinessError,
  onPromotionReadinessRetry,
  canReadShadowPerformance,
  canReadPromotionReadiness,
  onRetry,
  workspaceHeadingProps = {}
}) {
  return (
    <>
      <ShadowPerformanceDashboard
        summary={summary}
        isLoading={isLoading}
        error={error}
        canReadShadowPerformance={canReadShadowPerformance}
        onRetry={onRetry}
        headingProps={workspaceHeadingProps}
      />
      <PromotionReviewReadinessPanel
        report={promotionReadinessReport}
        isLoading={promotionReadinessIsLoading}
        error={promotionReadinessError}
        canReadPromotionReadiness={canReadPromotionReadiness}
        onRetry={onPromotionReadinessRetry}
      />
    </>
  );
}
