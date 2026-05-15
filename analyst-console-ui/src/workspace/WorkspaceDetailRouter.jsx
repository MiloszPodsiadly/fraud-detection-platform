import { useEffect, useMemo } from "react";
import { AlertDetailsPage } from "../pages/AlertDetailsPage.jsx";
import { FraudCaseDetailsPage } from "../pages/FraudCaseDetailsPage.jsx";

export function WorkspaceDetailRouter({
  selectedAlertId,
  selectedFraudCaseId,
  alertQueueState,
  alertSummaryRuntimeState = "available",
  session,
  apiClient,
  canReadAlerts,
  canReadFraudCases,
  workspacePage,
  workspaceLabel,
  onCloseSelection,
  onRefreshDashboard
}) {
  const selectedAlertSummary = useMemo(
    () => alertQueueState?.page?.content?.find((alert) => alert.alertId === selectedAlertId),
    [alertQueueState?.page?.content, selectedAlertId]
  );

  useEffect(() => {
    if (!selectedAlertId && !selectedFraudCaseId) {
      return;
    }
    window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  }, [selectedAlertId, selectedFraudCaseId]);

  function closeAndRestoreFocus() {
    const originKey = selectedAlertId
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

  if (selectedAlertId && apiClient) {
    return (
      <AlertDetailsPage
        alertId={selectedAlertId}
        alertSummary={selectedAlertSummary}
        alertSummaryRuntimeState={alertSummaryRuntimeState}
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

function workspaceLabelFor(workspacePage) {
  return {
    analyst: "Fraud Case",
    fraudTransaction: "Fraud Transaction",
    transactionScoring: "Transaction Scoring",
    compliance: "Compliance",
    reports: "Reports"
  }[workspacePage] || "Workspace";
}

function findDetailOrigin(originKey) {
  if (!originKey) {
    return null;
  }
  return [...document.querySelectorAll("[data-detail-origin]")]
    .find((element) => element.getAttribute("data-detail-origin") === originKey) || null;
}
