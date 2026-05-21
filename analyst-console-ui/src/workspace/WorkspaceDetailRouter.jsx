import { useEffect, useMemo } from "react";
import { EmptyState } from "../components/EmptyState.jsx";
import { AlertDetailsPage } from "../pages/AlertDetailsPage.jsx";
import { AlertReadOnlyContextPage } from "../pages/AlertReadOnlyContextPage.jsx";
import { FraudCaseDetailsPage } from "../pages/FraudCaseDetailsPage.jsx";
import { WORKSPACE_DETAIL_RUNTIME_STATE } from "./workspaceRuntimeStates.js";

export function WorkspaceDetailRouter({
  selectedAlertId,
  selectedFraudCaseId,
  selectedLinkedAlertContext = false,
  alertQueueState,
  alertSummaryRuntimeState,
  session,
  apiClient,
  canReadAlerts,
  canReadFraudCases,
  workspacePage,
  workspaceLabel,
  selectedSuspiciousTransactionId,
  sourceSuspiciousTransaction,
  sourceSuspiciousTransactionLoading = false,
  sourceSuspiciousTransactionError = null,
  onCloseSelection,
  onRefreshDashboard
}) {
  const selectedAlertSummary = useMemo(
    () => alertQueueState?.page?.content?.find((alert) => alert.alertId === selectedAlertId),
    [alertQueueState?.page?.content, selectedAlertId]
  );
  const hasSuspiciousBridgeRoute = workspacePage === "suspiciousTransactions"
    && selectedLinkedAlertContext === true
    && Boolean(selectedSuspiciousTransactionId);
  const linkedAlertContextClient = useMemo(
    () => hasSuspiciousBridgeRoute ? createLinkedAlertContextClient(apiClient) : null,
    [apiClient, hasSuspiciousBridgeRoute]
  );

  useEffect(() => {
    if (!selectedAlertId && !selectedFraudCaseId && !hasSuspiciousBridgeRoute) {
      return;
    }
    window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  }, [hasSuspiciousBridgeRoute, selectedAlertId, selectedFraudCaseId]);

  function closeAndRestoreFocus() {
    const originKey = selectedLinkedAlertContext && selectedSuspiciousTransactionId
      ? `suspicious-${selectedSuspiciousTransactionId}`
      : selectedAlertId
      ? `alert-${selectedAlertId}`
      : selectedFraudCaseId
        ? `fraud-case-${selectedFraudCaseId}`
        : null;
    onCloseSelection();
    window.setTimeout(() => {
      const origin = findDetailOrigin(originKey);
      const fallback = document.querySelector("[data-workspace-heading]");
      (origin || fallback)?.focus?.();
    }, 0);
  }

  if (workspacePage === "suspiciousTransactions" && selectedAlertId) {
    return renderBridgeState({
      title: "Invalid linked alert context",
      message: "Linked alert context must be resolved by source suspicious transaction.",
      onBack: closeAndRestoreFocus
    });
  }

  if (workspacePage === "suspiciousTransactions" && selectedLinkedAlertContext === true && !selectedSuspiciousTransactionId) {
    return renderBridgeState({
      title: "Invalid linked alert context",
      message: "Linked alert context requires a source suspicious transaction.",
      onBack: closeAndRestoreFocus
    });
  }

  if (hasSuspiciousBridgeRoute && (sourceSuspiciousTransactionLoading || !sourceSuspiciousTransaction) && !sourceSuspiciousTransactionError) {
    return renderBridgeState({
      title: "Verifying linked alert context",
      message: "Verifying linked alert context",
      onBack: closeAndRestoreFocus
    });
  }

  if (hasSuspiciousBridgeRoute && sourceSuspiciousTransactionError) {
    return renderBridgeState({
      title: "Linked alert context unavailable",
      message: "Linked alert context is unavailable.",
      onBack: closeAndRestoreFocus
    });
  }

  if (hasSuspiciousBridgeRoute) {
    return (
      <AlertReadOnlyContextPage
        suspiciousTransactionId={selectedSuspiciousTransactionId}
        linkedAlertContextClient={linkedAlertContextClient}
        canReadAlert={canReadAlerts}
        workspaceLabel={workspaceLabel || workspaceLabelFor(workspacePage)}
        onBack={closeAndRestoreFocus}
      />
    );
  }

  if (selectedAlertId && apiClient) {
    const effectiveAlertSummaryRuntimeState = alertSummaryRuntimeState
      || (alertQueueState ? WORKSPACE_DETAIL_RUNTIME_STATE.AVAILABLE : WORKSPACE_DETAIL_RUNTIME_STATE.NOT_MOUNTED);
    return (
      <AlertDetailsPage
        alertId={selectedAlertId}
        alertSummary={selectedAlertSummary}
        alertSummaryRuntimeState={effectiveAlertSummaryRuntimeState}
        session={session}
        apiClient={apiClient}
        canReadAlert={canReadAlerts}
        workspaceLabel={workspaceLabel || workspaceLabelFor(workspacePage)}
        onBack={closeAndRestoreFocus}
        onDecisionSubmitted={onRefreshDashboard}
      />
    );
  }

  if (selectedFraudCaseId && apiClient) {
    return (
      <FraudCaseDetailsPage
        caseId={selectedFraudCaseId}
        session={session}
        apiClient={apiClient}
        canReadFraudCase={canReadFraudCases}
        workspaceLabel={workspaceLabel || workspaceLabelFor(workspacePage)}
        onBack={closeAndRestoreFocus}
        onCaseUpdated={onRefreshDashboard}
      />
    );
  }

  return null;
}

function renderBridgeState({ title, message, onBack }) {
  return (
    <section className="detailsLayout pageEnter">
      <div className="panel detailsMain">
        <EmptyState title={title} message={message} />
        <button className="secondaryButton" type="button" onClick={onBack}>
          Back
        </button>
      </div>
    </section>
  );
}

function workspaceLabelFor(workspacePage) {
  return {
    analyst: "Fraud Case",
    fraudTransaction: "Fraud Transaction",
    transactionScoring: "Transaction Scoring",
    compliance: "Compliance",
    reports: "Reports"
  }[workspacePage] || "Workspace";
}

function createLinkedAlertContextClient(apiClient) {
  if (!apiClient || typeof apiClient.getSuspiciousTransactionLinkedAlertContext !== "function") {
    return null;
  }

  return Object.freeze({
    getSuspiciousTransactionLinkedAlertContext: (suspiciousTransactionId, requestOptions) =>
      apiClient.getSuspiciousTransactionLinkedAlertContext(suspiciousTransactionId, requestOptions)
  });
}

function findDetailOrigin(originKey) {
  if (!originKey) {
    return null;
  }
  return [...document.querySelectorAll("[data-detail-origin]")]
    .find((element) => element.getAttribute("data-detail-origin") === originKey) || null;
}
