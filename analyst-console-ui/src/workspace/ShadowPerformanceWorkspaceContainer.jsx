import { ShadowPerformanceDashboardPage } from "../pages/ShadowPerformanceDashboardPage.jsx";

export function ShadowPerformanceWorkspaceContainer({
  headingLabel = "Shadow Performance Summary",
  summaryState,
  promotionReadinessState = { report: null, isLoading: false, error: null },
  modelCardState = { card: null, isLoading: false, error: null },
  exactModelShadowPerformanceState = {
    summary: null, selector: null, isLoading: false, error: null,
    load: () => null, refresh: () => null
  },
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
  canAuditEmergencyModelRetirement
}) {
  return (
    <ShadowPerformanceDashboardPage
      summary={summaryState.summary}
      isLoading={summaryState.isLoading}
      error={summaryState.error}
      promotionReadinessReport={promotionReadinessState.report}
      promotionReadinessIsLoading={promotionReadinessState.isLoading}
      promotionReadinessError={promotionReadinessState.error}
      onPromotionReadinessRetry={promotionReadinessState.refresh}
      modelCard={modelCardState.card}
      modelCardIsLoading={modelCardState.isLoading}
      modelCardError={modelCardState.error}
      onModelCardRetry={modelCardState.refresh}
      exactModelShadowPerformanceState={exactModelShadowPerformanceState}
      canReadShadowPerformance={canReadShadowPerformance}
      canReadPromotionReadiness={canReadPromotionReadiness}
      canReadModelCard={canReadModelCard}
      canReadExactModelShadowPerformance={canReadExactModelShadowPerformance}
      modelPromotionWorkflow={modelPromotionWorkflow}
      canReadModelPromotion={canReadModelPromotion}
      canRegisterModelPromotion={canRegisterModelPromotion}
      canProposeModelPromotion={canProposeModelPromotion}
      canApproveModelPromotion={canApproveModelPromotion}
      canRejectModelPromotion={canRejectModelPromotion}
      canRetireModelPromotion={canRetireModelPromotion}
      canRequestEmergencyModelRetirement={canRequestEmergencyModelRetirement}
      canAuthorizeEmergencyModelRetirement={canAuthorizeEmergencyModelRetirement}
      canAuditEmergencyModelRetirement={canAuditEmergencyModelRetirement}
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
