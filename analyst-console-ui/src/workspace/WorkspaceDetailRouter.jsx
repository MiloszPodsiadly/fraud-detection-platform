import { useEffect, useMemo } from "react";
import { createAlertReadOnlyBridgeApiClient } from "../api/alertReadOnlyBridgeApi.js";
import { EmptyState } from "../components/EmptyState.jsx";
import { AlertDetailsPage } from "../pages/AlertDetailsPage.jsx";
import { FraudCaseDetailsPage } from "../pages/FraudCaseDetailsPage.jsx";
import { WORKSPACE_DETAIL_RUNTIME_STATE } from "./workspaceRuntimeStates.js";

export function WorkspaceDetailRouter({
  selectedAlertId,
  selectedFraudCaseId,
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
    && Boolean(selectedAlertId)
    && Boolean(selectedSuspiciousTransactionId);
  const sourceLinkedAlertMatches = normalizeBridgeId(sourceSuspiciousTransaction?.linkedAlertId) === normalizeBridgeId(selectedAlertId);
  const readOnlyAlertContext = hasSuspiciousBridgeRoute
    && Boolean(sourceSuspiciousTransaction)
    && sourceLinkedAlertMatches;
  const alertDetailApiClient = useMemo(
    () => readOnlyAlertContext ? createAlertReadOnlyBridgeApiClient(apiClient) : apiClient,
    [apiClient, readOnlyAlertContext]
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

  if (workspacePage === "suspiciousTransactions" && selectedAlertId && !selectedSuspiciousTransactionId) {
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

  if (hasSuspiciousBridgeRoute && !sourceLinkedAlertMatches) {
    return renderBridgeState({
      title: "Invalid linked alert context",
      message: "Linked alert context could not be verified.",
      onBack: closeAndRestoreFocus
    });
  }

  if (selectedAlertId && alertDetailApiClient) {
    const effectiveAlertSummaryRuntimeState = alertSummaryRuntimeState
      || (alertQueueState ? WORKSPACE_DETAIL_RUNTIME_STATE.AVAILABLE : WORKSPACE_DETAIL_RUNTIME_STATE.NOT_MOUNTED);
    return (
      <AlertDetailsPage
        alertId={selectedAlertId}
        alertSummary={selectedAlertSummary}
        alertSummaryRuntimeState={effectiveAlertSummaryRuntimeState}
        session={session}
        apiClient={alertDetailApiClient}
        canReadAlert={canReadAlerts}
        readOnlyContext={readOnlyAlertContext}
        sourceSuspiciousTransaction={readOnlyAlertContext ? sourceSuspiciousTransaction : null}
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

function normalizeBridgeId(value) {
  return value === null || value === undefined ? "" : String(value).trim();
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
