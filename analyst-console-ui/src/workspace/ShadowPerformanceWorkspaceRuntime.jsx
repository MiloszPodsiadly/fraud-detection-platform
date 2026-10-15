import { ShadowPerformanceWorkspaceContainer } from "./ShadowPerformanceWorkspaceContainer.jsx";
import { usePromotionReviewReadinessReport } from "./usePromotionReviewReadinessReport.js";
import { useModelCard } from "./useModelCard.js";
import { useShadowPerformanceSummary } from "./useShadowPerformanceSummary.js";
import { useExactModelShadowPerformance } from "./useExactModelShadowPerformance.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function ShadowPerformanceWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  children
}) {
  const {
    canReadExactModelShadowPerformance,
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

  function refreshWorkspace() {
    summaryState.refresh();
    promotionReadinessState.refresh();
    modelCardState.refresh();
    exactModelShadowPerformanceState.refresh();
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
      />
    ),
    error: summaryState.error,
    refreshWorkspace
  }));
}
