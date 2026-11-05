import { useEffect } from "react";
import { FraudTransactionWorkspaceContainer } from "./FraudTransactionWorkspaceContainer.jsx";
import { useAlertQueue } from "./useAlertQueue.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function FraudTransactionWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  setCounterValue,
  onOpenAlert,
  children
}) {
  const { canReadAlerts } = useWorkspaceRuntime();
  const alertQueueState = useAlertQueue({
    enabled: sharedWorkspaceReadsEnabled && canReadAlerts === true
  });

  useEffect(() => {
    if (canReadAlerts === true && alertQueueState.hasLoaded && !alertQueueState.error) {
      setCounterValue("alerts", alertQueueState.page.totalElements);
    }
  }, [alertQueueState.error, alertQueueState.hasLoaded, alertQueueState.page.totalElements, canReadAlerts, setCounterValue]);

  function changeAlertPage(page) {
    alertQueueState.setRequest((current) => ({ ...current, page }));
  }

  function changeAlertPageSize(size) {
    alertQueueState.setRequest({ page: 0, size });
  }

  function refreshWorkspace() {
    alertQueueState.refresh();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <FraudTransactionWorkspaceContainer
        headingLabel={route.heading.label}
        alertQueueState={alertQueueState}
        onRetryWorkspace={refreshWorkspace}
        onPageChange={changeAlertPage}
        onPageSizeChange={changeAlertPageSize}
        onOpenAlert={onOpenAlert}
      />
    ),
    navigationState: {
      alertPage: alertQueueState.hasLoaded && !alertQueueState.error
        ? alertQueueState.page
        : null
    },
    detailRouterState: {
      alertQueueState
    },
    error: alertQueueState.error,
    refreshWorkspace
  }));
}
