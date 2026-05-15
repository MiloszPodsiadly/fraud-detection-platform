import { useCallback } from "react";
import { getSessionStateForApiError, getSessionStateForProvider } from "../auth/sessionState.js";
import { useFraudCaseWorkQueue } from "../fraudCases/useFraudCaseWorkQueue.js";
import { useFraudCaseWorkQueueSummary } from "../fraudCases/useFraudCaseWorkQueueSummary.js";

export function useAnalystWorkspaceRuntime({
  workspacePage,
  sharedWorkspaceReadsEnabled,
  canReadFraudCases,
  session,
  authProvider,
  setSessionState
}) {
  const fraudCaseReadsEnabled = workspacePage === "analyst"
    && sharedWorkspaceReadsEnabled
    && canReadFraudCases === true;
  const handleWorkQueueSessionError = useCallback((apiError) => {
    setSessionState(getSessionStateForApiError(session, apiError) || getSessionStateForProvider(session, authProvider));
  }, [authProvider, session, setSessionState]);

  const workQueueState = useFraudCaseWorkQueue({
    enabled: fraudCaseReadsEnabled,
    onSessionError: handleWorkQueueSessionError
  });
  const summaryState = useFraudCaseWorkQueueSummary({
    enabled: fraudCaseReadsEnabled
  });

  return {
    workQueueState,
    summaryState
  };
}
