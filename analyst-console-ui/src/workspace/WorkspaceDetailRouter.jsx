import { useEffect, useMemo } from "react";
import { AlertDetailsPage } from "../pages/AlertDetailsPage.jsx";
import { FraudCaseDetailsPage } from "../pages/FraudCaseDetailsPage.jsx";

export function WorkspaceDetailRouter({
  selectedAlertId,
  selectedFraudCaseId,
  alertQueueState,
  session,
  apiClient,
  onCloseSelection,
  onRefreshDashboard
}) {
  const selectedAlertSummary = useMemo(
    () => alertQueueState.page.content.find((alert) => alert.alertId === selectedAlertId),
    [alertQueueState.page.content, selectedAlertId]
  );

  useEffect(() => {
    if (!selectedAlertId && !selectedFraudCaseId) {
      return;
    }
    window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  }, [selectedAlertId, selectedFraudCaseId]);

  if (selectedAlertId && apiClient) {
    return (
      <AlertDetailsPage
        alertId={selectedAlertId}
        alertSummary={selectedAlertSummary}
        session={session}
        apiClient={apiClient}
        onBack={onCloseSelection}
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
        onBack={onCloseSelection}
        onCaseUpdated={onRefreshDashboard}
      />
    );
  }

  return null;
}
