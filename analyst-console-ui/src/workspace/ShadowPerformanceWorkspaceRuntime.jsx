import { ShadowPerformanceWorkspaceContainer } from "./ShadowPerformanceWorkspaceContainer.jsx";
import { useShadowPerformanceSummary } from "./useShadowPerformanceSummary.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function ShadowPerformanceWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  children
}) {
  const { canReadShadowPerformance } = useWorkspaceRuntime();
  const summaryState = useShadowPerformanceSummary({
    enabled: sharedWorkspaceReadsEnabled && canReadShadowPerformance === true
  });

  function refreshWorkspace() {
    summaryState.refresh();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <ShadowPerformanceWorkspaceContainer
        headingLabel={route.heading.label}
        summaryState={summaryState}
        canReadShadowPerformance={canReadShadowPerformance}
      />
    ),
    error: summaryState.error,
    refreshWorkspace
  }));
}
