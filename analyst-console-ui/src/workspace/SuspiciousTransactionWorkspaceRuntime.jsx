import { SuspiciousTransactionWorkspacePage } from "../pages/SuspiciousTransactionWorkspacePage.jsx";
import { useSuspiciousTransactionReadView } from "./useSuspiciousTransactionReadView.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function SuspiciousTransactionWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  selectedSuspiciousTransactionId,
  onOpenSuspiciousLinkedAlertContext,
  onOpenSuspiciousTransaction,
  onCloseSuspiciousTransaction,
  children
}) {
  const { canReadAlerts, canReadSuspiciousTransactions } = useWorkspaceRuntime();
  const readViewState = useSuspiciousTransactionReadView({
    enabled: sharedWorkspaceReadsEnabled && canReadSuspiciousTransactions === true,
    selectedSuspiciousTransactionId
  });

  function refreshWorkspace() {
    if (selectedSuspiciousTransactionId) {
      return readViewState.refreshDetail();
    }
    return readViewState.refreshList();
  }

  return children(createWorkspaceRuntimeResult({
    workspaceContent: (
      <SuspiciousTransactionWorkspacePage
        headingLabel={route.heading.label}
        readViewState={readViewState}
        canReadSuspiciousTransactions={canReadSuspiciousTransactions}
        canReadAlerts={canReadAlerts}
        selectedSuspiciousTransactionId={selectedSuspiciousTransactionId}
        onOpenSuspiciousTransaction={onOpenSuspiciousTransaction}
        onOpenSuspiciousLinkedAlertContext={onOpenSuspiciousLinkedAlertContext}
        onCloseSuspiciousTransaction={onCloseSuspiciousTransaction}
      />
    ),
    detailRouterState: {
      sourceSuspiciousTransaction: selectedSuspiciousTransactionId ? readViewState.detail : null,
      sourceSuspiciousTransactionLoading: readViewState.isLoadingDetail,
      sourceSuspiciousTransactionError: readViewState.detailError
    },
    error: selectedSuspiciousTransactionId ? readViewState.detailError : readViewState.listError,
    refreshWorkspace
  }));
}
