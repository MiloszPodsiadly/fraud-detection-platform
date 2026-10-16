import { ShadowPerformanceDashboard } from "../components/ShadowPerformanceDashboard.jsx";
import { PromotionReviewReadinessPanel } from "../components/PromotionReviewReadinessPanel.jsx";
import { ModelCardPanel } from "../components/ModelCardPanel.jsx";
import { ExactModelShadowPerformancePanel } from "../components/ExactModelShadowPerformancePanel.jsx";

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
  exactModelShadowPerformanceState,
  canReadShadowPerformance,
  canReadPromotionReadiness,
  canReadModelCard,
  canReadExactModelShadowPerformance,
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
      <ExactModelShadowPerformancePanel
        state={exactModelShadowPerformanceState}
        canRead={canReadExactModelShadowPerformance}
        modelCard={modelCard}
      />
    </>
  );
}
