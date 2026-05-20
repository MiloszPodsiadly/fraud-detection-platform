import { SuspiciousTransactionWorkspacePage } from "../pages/SuspiciousTransactionWorkspacePage.jsx";
import { useSuspiciousTransactionReadView } from "./useSuspiciousTransactionReadView.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function SuspiciousTransactionWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  selectedSuspiciousTransactionId,
  onOpenSuspiciousTransaction,
  onCloseSuspiciousTransaction,
  children
}) {
  const { canReadSuspiciousTransactions } = useWorkspaceRuntime();
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
        selectedSuspiciousTransactionId={selectedSuspiciousTransactionId}
        onOpenSuspiciousTransaction={onOpenSuspiciousTransaction}
        onCloseSuspiciousTransaction={onCloseSuspiciousTransaction}
      />
    ),
    error: selectedSuspiciousTransactionId ? readViewState.detailError : readViewState.listError,
    refreshWorkspace
  }));
}
