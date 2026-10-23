import { ShadowPerformanceDashboard } from "../components/ShadowPerformanceDashboard.jsx";
import { PromotionReviewReadinessPanel } from "../components/PromotionReviewReadinessPanel.jsx";
import { ModelCardPanel } from "../components/ModelCardPanel.jsx";
import { ExactModelShadowPerformancePanel } from "../components/ExactModelShadowPerformancePanel.jsx";
import { ModelPromotionPanel } from "../components/ModelPromotionPanel.jsx";

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
  modelPromotionWorkflow,
  canReadModelPromotion,
  canRegisterModelPromotion,
  canProposeModelPromotion,
  canApproveModelPromotion,
  canRejectModelPromotion,
  canRetireModelPromotion,
  canRequestEmergencyModelRetirement,
  canAuthorizeEmergencyModelRetirement,
  canAuditEmergencyModelRetirement,
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
      {modelPromotionWorkflow && <ModelPromotionPanel
        workflow={modelPromotionWorkflow}
        modelCard={modelCard}
        canRead={canReadModelPromotion}
        canRegister={canRegisterModelPromotion}
        canPropose={canProposeModelPromotion}
        canApprove={canApproveModelPromotion}
        canReject={canRejectModelPromotion}
        canRetire={canRetireModelPromotion}
        canRequestEmergency={canRequestEmergencyModelRetirement}
        canAuthorizeEmergency={canAuthorizeEmergencyModelRetirement}
        canAuditEmergency={canAuditEmergencyModelRetirement}
      />}
    </>
  );
}
