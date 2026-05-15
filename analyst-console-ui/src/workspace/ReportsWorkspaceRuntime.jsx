import { ReportsWorkspaceContainer } from "./ReportsWorkspaceContainer.jsx";
import { useGovernanceAnalytics } from "./useGovernanceAnalytics.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function ReportsWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  children
}) {
  const { canReadGovernanceAdvisories } = useWorkspaceRuntime();
  const analyticsState = useGovernanceAnalytics({
    enabled: sharedWorkspaceReadsEnabled && canReadGovernanceAdvisories === true
  });

  function refreshWorkspace() {
    analyticsState.refresh();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <ReportsWorkspaceContainer
        headingLabel={route.heading.label}
        analyticsState={analyticsState}
      />
    ),
    navigationState: {
      governanceAnalytics: analyticsState.analytics
    },
    error: analyticsState.error,
    refreshWorkspace
  }));
}
