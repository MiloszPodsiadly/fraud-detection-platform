import { ShadowPerformanceDashboard } from "../components/ShadowPerformanceDashboard.jsx";
import { PromotionReviewReadinessPanel } from "../components/PromotionReviewReadinessPanel.jsx";
import { ModelCardPanel } from "../components/ModelCardPanel.jsx";

export function ShadowPerformanceDashboardPage({
  summary,
  isLoading,
  error,
  promotionReadinessReport,
  promotionReadinessIsLoading,
  promotionReadinessError,
  onPromotionReadinessRetry,
  modelCard,
  modelCardIsLoading,
  modelCardError,
  onModelCardRetry,
  canReadShadowPerformance,
  canReadPromotionReadiness,
  canReadModelCard,
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
      <ModelCardPanel
        card={modelCard}
        isLoading={modelCardIsLoading}
        error={modelCardError}
        canReadModelCard={canReadModelCard}
        onRetry={onModelCardRetry}
      />
    </>
  );
}
