import { ShadowPerformanceWorkspaceContainer } from "./ShadowPerformanceWorkspaceContainer.jsx";
import { usePromotionReviewReadinessReport } from "./usePromotionReviewReadinessReport.js";
import { useModelCard } from "./useModelCard.js";
import { useShadowPerformanceSummary } from "./useShadowPerformanceSummary.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function ShadowPerformanceWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  children
}) {
  const { canReadModelCard, canReadPromotionReadiness, canReadShadowPerformance } = useWorkspaceRuntime();
  const summaryState = useShadowPerformanceSummary({
    enabled: sharedWorkspaceReadsEnabled && canReadShadowPerformance === true
  });
  const promotionReadinessState = usePromotionReviewReadinessReport({
    enabled: sharedWorkspaceReadsEnabled && canReadPromotionReadiness === true
  });
  const modelCardState = useModelCard({
    enabled: sharedWorkspaceReadsEnabled && canReadModelCard === true
  });

  function refreshWorkspace() {
    summaryState.refresh();
    promotionReadinessState.refresh();
    modelCardState.refresh();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <ShadowPerformanceWorkspaceContainer
        headingLabel={route.heading.label}
        summaryState={summaryState}
        promotionReadinessState={promotionReadinessState}
        modelCardState={modelCardState}
        canReadShadowPerformance={canReadShadowPerformance}
        canReadPromotionReadiness={canReadPromotionReadiness}
        canReadModelCard={canReadModelCard}
      />
    ),
    error: summaryState.error,
    refreshWorkspace
  }));
}
