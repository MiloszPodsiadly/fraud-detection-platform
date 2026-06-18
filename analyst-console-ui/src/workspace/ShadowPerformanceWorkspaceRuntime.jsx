import { ShadowPerformanceWorkspaceContainer } from "./ShadowPerformanceWorkspaceContainer.jsx";
import { usePromotionReviewReadinessReport } from "./usePromotionReviewReadinessReport.js";
import { useShadowPerformanceSummary } from "./useShadowPerformanceSummary.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function ShadowPerformanceWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  children
}) {
  const { canReadPromotionReadiness, canReadShadowPerformance } = useWorkspaceRuntime();
  const summaryState = useShadowPerformanceSummary({
    enabled: sharedWorkspaceReadsEnabled && canReadShadowPerformance === true
  });
  const promotionReadinessState = usePromotionReviewReadinessReport({
    enabled: sharedWorkspaceReadsEnabled && canReadPromotionReadiness === true
  });

  function refreshWorkspace() {
    summaryState.refresh();
    promotionReadinessState.refresh();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <ShadowPerformanceWorkspaceContainer
        headingLabel={route.heading.label}
        summaryState={summaryState}
        promotionReadinessState={promotionReadinessState}
        canReadShadowPerformance={canReadShadowPerformance}
        canReadPromotionReadiness={canReadPromotionReadiness}
      />
    ),
    error: summaryState.error,
    refreshWorkspace
  }));
}
