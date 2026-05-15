import { AnalystWorkspaceContainer } from "./AnalystWorkspaceContainer.jsx";
import { useAnalystWorkspaceRuntime } from "./useAnalystWorkspaceRuntime.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function AnalystWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  setSessionState,
  onOpenFraudCase,
  children
}) {
  const {
    session,
    authProvider,
    canReadFraudCases
  } = useWorkspaceRuntime();
  const { workQueueState, summaryState } = useAnalystWorkspaceRuntime({
    workspacePage: route.key,
    sharedWorkspaceReadsEnabled,
    canReadFraudCases,
    session,
    authProvider,
    setSessionState
  });

  function refreshWorkspace() {
    summaryState.retry();
    workQueueState.refreshFirstSlice();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <AnalystWorkspaceContainer
        canReadFraudCases={canReadFraudCases}
        headingLabel={route.heading.label}
        workQueueState={workQueueState}
        summaryState={summaryState}
        onOpenFraudCase={onOpenFraudCase}
      />
    ),
    navigationState: {
      fraudCaseSummary: summaryState.summary,
      fraudCaseSummaryError: summaryState.error,
      isFraudCaseSummaryLoading: summaryState.isLoading
    },
    error: workQueueState.error,
    refreshWorkspace
  }));
}
