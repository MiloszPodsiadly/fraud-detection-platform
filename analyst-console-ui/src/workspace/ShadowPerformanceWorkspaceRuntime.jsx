import { ShadowPerformanceWorkspaceContainer } from "./ShadowPerformanceWorkspaceContainer.jsx";
import { usePromotionReviewReadinessReport } from "./usePromotionReviewReadinessReport.js";
import { useModelCard } from "./useModelCard.js";
import { useShadowPerformanceSummary } from "./useShadowPerformanceSummary.js";
import { useExactModelShadowPerformance } from "./useExactModelShadowPerformance.js";
import { useModelPromotionWorkflow } from "./useModelPromotionWorkflow.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function ShadowPerformanceWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  children
}) {
  const {
    canReadExactModelShadowPerformance,
    canReadModelPromotion,
    canRegisterModelPromotion,
    canProposeModelPromotion,
    canApproveModelPromotion,
    canRejectModelPromotion,
    canRetireModelPromotion,
    canRequestEmergencyModelRetirement,
    canAuthorizeEmergencyModelRetirement,
    canAuditEmergencyModelRetirement,
    canReadModelCard,
    canReadPromotionReadiness,
    canReadShadowPerformance
  } = useWorkspaceRuntime();
  const summaryState = useShadowPerformanceSummary({
    enabled: sharedWorkspaceReadsEnabled && canReadShadowPerformance === true
  });
  const promotionReadinessState = usePromotionReviewReadinessReport({
    enabled: sharedWorkspaceReadsEnabled && canReadPromotionReadiness === true
  });
  const modelCardState = useModelCard({
    enabled: sharedWorkspaceReadsEnabled && canReadModelCard === true
  });
  const exactModelShadowPerformanceState = useExactModelShadowPerformance({
    enabled: sharedWorkspaceReadsEnabled && canReadExactModelShadowPerformance === true
  });
  const modelPromotionWorkflow = useModelPromotionWorkflow({
    enabled: sharedWorkspaceReadsEnabled && canReadModelPromotion === true
  });

  function refreshWorkspace() {
    summaryState.refresh();
    promotionReadinessState.refresh();
    modelCardState.refresh();
    exactModelShadowPerformanceState.refresh();
    modelPromotionWorkflow.load();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <ShadowPerformanceWorkspaceContainer
        headingLabel={route.heading.label}
        summaryState={summaryState}
        promotionReadinessState={promotionReadinessState}
        modelCardState={modelCardState}
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
      />
    ),
    error: summaryState.error,
    refreshWorkspace
  }));
}
