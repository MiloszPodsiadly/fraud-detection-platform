import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";

const alertDetailsPageMock = vi.hoisted(() => vi.fn(({ canReadAlert, workspaceLabel, alertSummaryRuntimeState, apiClient, onBack }) => (
  <section>
    <h2>Alert detail {String(canReadAlert)}</h2>
    <span>{workspaceLabel}</span>
    <span>{alertSummaryRuntimeState}</span>
    <span>api methods {Object.keys(apiClient || {}).join(",")}</span>
    <button type="button" onClick={onBack}>Back to list</button>
  </section>
)));

const alertReadOnlyContextPageMock = vi.hoisted(() => vi.fn(({ canReadAlert, workspaceLabel, linkedAlertContextClient, suspiciousTransactionId, onBack }) => (
  <section>
    <h2>Dedicated read-only alert context {String(canReadAlert)}</h2>
    <span>{workspaceLabel}</span>
    <span>resolver id {suspiciousTransactionId || "none"}</span>
    <span>read client methods {Object.keys(linkedAlertContextClient || {}).join(",")}</span>
    <button type="button" onClick={onBack}>Back to list</button>
  </section>
)));

vi.mock("../pages/AlertDetailsPage.jsx", () => ({
  AlertDetailsPage: alertDetailsPageMock
}));

vi.mock("../pages/AlertReadOnlyContextPage.jsx", () => ({
  AlertReadOnlyContextPage: alertReadOnlyContextPageMock
}));

vi.mock("../pages/FraudCaseDetailsPage.jsx", () => ({
  FraudCaseDetailsPage: ({ canReadFraudCase, workspaceLabel, onBack }) => (
    <section>
      <h2>Fraud case detail {String(canReadFraudCase)}</h2>
      <span>{workspaceLabel}</span>
      <button type="button" onClick={onBack}>Back to list</button>
    </section>
  )
}));

import { WorkspaceDetailRouter } from "./WorkspaceDetailRouter.jsx";
import { WORKSPACE_DETAIL_RUNTIME_STATE } from "./workspaceRuntimeStates.js";

describe("WorkspaceDetailRouter", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("passes alert read capability and restores focus to the originating alert control", async () => {
    const onCloseSelection = vi.fn();
    render(
      <>
        <button type="button" data-detail-origin="alert-alert-1">Origin alert</button>
        <WorkspaceDetailRouter
          selectedAlertId="alert-1"
          selectedFraudCaseId={null}
          alertQueueState={{ page: { content: [{ alertId: "alert-1" }] } }}
          session={{ userId: "analyst-1", authorities: [] }}
          apiClient={{}}
          canReadAlerts
          canReadFraudCases={false}
          workspacePage="fraudTransaction"
          onCloseSelection={onCloseSelection}
          onRefreshDashboard={vi.fn()}
        />
      </>
    );

    expect(screen.getByRole("heading", { name: "Alert detail true" })).toBeInTheDocument();
    expect(screen.getByText("Fraud Transaction")).toBeInTheDocument();
    expect(screen.getByText(WORKSPACE_DETAIL_RUNTIME_STATE.AVAILABLE)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Back to list" }));
    expect(onCloseSelection).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole("button", { name: "Origin alert" })).toHaveFocus());
  });

  it("passes not-mounted detail runtime state when alert queue owner is absent", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId="alert-1"
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: [] }}
        apiClient={{}}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="analyst"
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByText(WORKSPACE_DETAIL_RUNTIME_STATE.NOT_MOUNTED)).toBeInTheDocument();
  });

  it("AlertDetailRequiresAlertReadAuthorityTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts={false}
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Dedicated read-only alert context false" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert detail false" })).not.toBeInTheDocument();
  });

  it("SuspiciousTransactionAuthorityDoesNotGrantAlertDetailAccessTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts={false}
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByText("read client methods getSuspiciousTransactionLinkedAlertContext")).toBeInTheDocument();
  });

  it("FrontendGuardDoesNotClaimSecurityBoundaryTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Dedicated read-only alert context true" })).toBeInTheDocument();
    expect(screen.getByText("read client methods getSuspiciousTransactionLinkedAlertContext")).toBeInTheDocument();
  });

  it("alertIdOnlySuspiciousWorkspaceRouteShowsInvalidBridgeContext", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId="alert-1"
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByText("Invalid linked alert context")).toBeInTheDocument();
    expect(screen.getByText("Linked alert context must be resolved by source suspicious transaction.")).toBeInTheDocument();
  });

  it("alertIdOnlySuspiciousWorkspaceRouteDoesNotExposeAlertDetail", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId="alert-1"
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.queryByRole("heading", { name: "Alert detail true" })).not.toBeInTheDocument();
  });

  it("suspiciousWorkspaceAlertIdWithoutSuspiciousTransactionIdDoesNotCallGetAlertAsBridge", () => {
    const getAlert = vi.fn();
    render(
      <WorkspaceDetailRouter
        selectedAlertId="alert-1"
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(getAlert).not.toHaveBeenCalled();
    expect(screen.getByText("Invalid linked alert context")).toBeInTheDocument();
  });

  it("suspiciousLinkedContextRejectsClientSuppliedAlertId", () => {
    const getAlert = vi.fn();
    const getSuspiciousTransactionLinkedAlertContext = vi.fn();
    render(
      <WorkspaceDetailRouter
        selectedAlertId="alert-secret"
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert, getSuspiciousTransactionLinkedAlertContext, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-secret" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(getAlert).not.toHaveBeenCalled();
    expect(getSuspiciousTransactionLinkedAlertContext).not.toHaveBeenCalled();
    expect(screen.getByText("Invalid linked alert context")).toBeInTheDocument();
    expect(screen.getByText("Linked alert context must be resolved by source suspicious transaction.")).toBeInTheDocument();
  });

  it("suspiciousTransactionLinkedContextRouteUsesReadOnlyBridgeClient", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Dedicated read-only alert context true" })).toBeInTheDocument();
    expect(screen.getByText("read client methods getSuspiciousTransactionLinkedAlertContext")).toBeInTheDocument();
  });

  it("WorkspaceDetailRouterUsesSuspiciousTransactionIdForLinkedAlertContextTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(alertReadOnlyContextPageMock.mock.calls.at(-1)[0]).toEqual(expect.objectContaining({
      suspiciousTransactionId: "suspicious-1"
    }));
  });

  it("WorkspaceDetailRouterDoesNotPassSelectedAlertIdAsFetchInputTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-secret" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    const props = alertReadOnlyContextPageMock.mock.calls.at(-1)[0];
    expect(props).not.toHaveProperty("alertId");
    expect(props.suspiciousTransactionId).toBe("suspicious-1");
  });

  it("WorkspaceDetailRouterPassesMinimalPropsToAlertReadOnlyContextPageTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-secret" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(Object.keys(alertReadOnlyContextPageMock.mock.calls.at(-1)[0]).sort()).toEqual([
      "canReadAlert",
      "linkedAlertContextClient",
      "onBack",
      "suspiciousTransactionId",
      "workspaceLabel"
    ]);
  });

  it("WorkspaceDetailRouterDoesNotPassSourceSuspiciousTransactionToAlertReadOnlyContextPageTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-secret" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    const props = alertReadOnlyContextPageMock.mock.calls.at(-1)[0];
    expect(props).not.toHaveProperty("sourceSuspiciousTransaction");
    expect(props).not.toHaveProperty("sourceSuspiciousTransactionLoading");
    expect(props).not.toHaveProperty("sourceSuspiciousTransactionError");
  });

  it("WorkspaceDetailRouterDoesNotPassAlertIdOrLinkedAlertIdToAlertReadOnlyContextPageTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "linked-alert-secret" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    const props = alertReadOnlyContextPageMock.mock.calls.at(-1)[0];
    expect(props).not.toHaveProperty("alertId");
    expect(props).not.toHaveProperty("linkedAlertId");
  });

  it("WorkspaceDetailRouterDoesNotCreateGetAlertOnlyClientForSuspiciousBridgeTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    const props = alertReadOnlyContextPageMock.mock.calls.at(-1)[0];
    expect(Object.keys(props.linkedAlertContextClient || {})).toEqual(["getSuspiciousTransactionLinkedAlertContext"]);
    expect(props.linkedAlertContextClient).not.toHaveProperty("getAlert");
  });

  it("WorkspaceDetailRouterFailsClosedWhenSuspiciousTransactionIdMissingTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId=""
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.queryByRole("heading", { name: /Dedicated read-only alert context/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /Alert detail/ })).not.toBeInTheDocument();
  });

  it("suspiciousTransactionLinkedContextRouteWithoutLoadedSourceDoesNotCallGetAlert", () => {
    const getAlert = vi.fn();
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={null}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(getAlert).not.toHaveBeenCalled();
    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(2);
    expect(screen.queryByRole("heading", { name: "Alert detail true" })).not.toBeInTheDocument();
  });

  it("suspiciousTransactionLinkedContextRouteWhileSourceLoadsDoesNotCallGetAlert", () => {
    const getAlert = vi.fn();
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={null}
        sourceSuspiciousTransactionLoading
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(getAlert).not.toHaveBeenCalled();
    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(2);
  });

  it("WorkspaceDetailRouterRendersStaleSourceStateWhenSourceIdMismatchesRouteTest", () => {
    const getAlert = vi.fn();
    const getSuspiciousTransactionLinkedAlertContext = vi.fn();
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert, getSuspiciousTransactionLinkedAlertContext, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-2"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1" }}
        sourceSuspiciousTransactionLoading={false}
        sourceSuspiciousTransactionError={null}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(alertReadOnlyContextPageMock).not.toHaveBeenCalled();
    expect(getSuspiciousTransactionLinkedAlertContext).not.toHaveBeenCalled();
    expect(getAlert).not.toHaveBeenCalled();
    expect(screen.queryByText("Verifying linked alert context")).not.toBeInTheDocument();
    expect(screen.getByText("Linked alert context not ready")).toBeInTheDocument();
    expect(screen.getByText("Linked alert context is not ready for the selected suspicious transaction.")).toBeInTheDocument();
    expect(screen.queryByText(/suspicious-1/)).not.toBeInTheDocument();
    expect(screen.queryByText(/suspicious-2/)).not.toBeInTheDocument();
  });

  it("WorkspaceDetailRouterDoesNotTreatStaleSourceAsLoadingTest", () => {
    const getSuspiciousTransactionLinkedAlertContext = vi.fn();
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-2"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1" }}
        sourceSuspiciousTransactionLoading={false}
        sourceSuspiciousTransactionError={null}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.queryByText("Verifying linked alert context")).not.toBeInTheDocument();
    expect(screen.getByText("Linked alert context not ready")).toBeInTheDocument();
    expect(getSuspiciousTransactionLinkedAlertContext).not.toHaveBeenCalled();
    expect(screen.queryByRole("heading", { name: "Dedicated read-only alert context true" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert detail true" })).not.toBeInTheDocument();
  });

  it("WorkspaceDetailRouterStillShowsVerifyingWhenSourceIsMissingOrLoadingTest", () => {
    const commonProps = {
      selectedAlertId: null,
      selectedFraudCaseId: null,
      alertQueueState: undefined,
      session: { userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] },
      apiClient: { getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() },
      canReadAlerts: true,
      canReadFraudCases: false,
      workspacePage: "suspiciousTransactions",
      selectedLinkedAlertContext: true,
      selectedSuspiciousTransactionId: "suspicious-1",
      onCloseSelection: vi.fn(),
      onRefreshDashboard: vi.fn()
    };

    const { rerender } = render(
      <WorkspaceDetailRouter
        {...commonProps}
        sourceSuspiciousTransaction={null}
      />
    );
    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(2);
    expect(screen.queryByText("Linked alert context not ready")).not.toBeInTheDocument();

    rerender(
      <WorkspaceDetailRouter
        {...commonProps}
        sourceSuspiciousTransaction={null}
        sourceSuspiciousTransactionLoading
      />
    );
    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(2);
    expect(screen.queryByText("Linked alert context not ready")).not.toBeInTheDocument();
  });

  it("WorkspaceDetailRouterMountsAlertReadOnlyContextWhenSourceIdMatchesRouteTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-2"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-2", linkedAlertId: "alert-secret" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(alertReadOnlyContextPageMock).toHaveBeenCalledTimes(1);
    const props = alertReadOnlyContextPageMock.mock.calls[0][0];
    expect(props.suspiciousTransactionId).toBe("suspicious-2");
    expect(props).not.toHaveProperty("sourceSuspiciousTransaction");
    expect(props).not.toHaveProperty("alertId");
    expect(props).not.toHaveProperty("linkedAlertId");
  });

  it("suspiciousTransactionLinkedContextRouteWithSourceErrorDoesNotCallGetAlert", () => {
    const getAlert = vi.fn();
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={null}
        sourceSuspiciousTransactionError={new Error("raw source failure alert-secret suspicious-secret")}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(getAlert).not.toHaveBeenCalled();
    expect(screen.getByText("Linked alert context is unavailable.")).toBeInTheDocument();
    expect(screen.queryByText(/alert-secret/)).not.toBeInTheDocument();
    expect(screen.queryByText(/suspicious-secret/)).not.toBeInTheDocument();
  });

  it("WorkspaceDetailRouterDoesNotUseLinkedAlertIdForRouteReadinessTest", () => {
    const getAlert = vi.fn();
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert, getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-2" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(getAlert).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Dedicated read-only alert context true" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert detail true" })).not.toBeInTheDocument();
  });

  it("WorkspaceDetailRouterDoesNotPerformRelationshipValidationTest", () => {
    const routerSource = readFileSync(resolve(process.cwd(), "src/workspace/WorkspaceDetailRouter.jsx"), "utf8");

    // Architecture regression guard: runtime tests above prove behavior; this blocks relationship validation from returning.
    expect(routerSource).toContain("sourceReadinessState");
    expect(routerSource).toContain("sourceSuspiciousTransaction.suspiciousTransactionId");
    expect(routerSource).toContain("loadedId !== selectedId");
    expect(routerSource).toContain('"stale-source"');
    expect(routerSource).not.toContain("sourceSuspiciousTransaction.linkedAlertId");
    expect(routerSource).not.toContain("sourceSuspiciousTransaction.transactionId");
    expect(routerSource).not.toContain("sourceSuspiciousTransaction.customerId");
    expect(routerSource).not.toContain("sourceSuspiciousTransaction.accountId");
    expect(routerSource).not.toContain("sourceSuspiciousTransaction.correlationId");
    expect(routerSource).not.toContain("sourceSuspiciousTransaction.scoreDecisionId");
    expect(routerSource).not.toContain("linkedAlertId");
    expect(routerSource).not.toContain("transactionId ===");
    expect(routerSource).not.toContain("customerId ===");
    expect(routerSource).not.toContain("accountId ===");
    expect(routerSource).not.toContain("correlationId ===");
    expect(routerSource).not.toContain("scoreDecisionId ===");
  });

  it("suspiciousTransactionLinkedContextRouteWithMatchingSourceCallsReadOnlyBridge", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Dedicated read-only alert context true" })).toBeInTheDocument();
    expect(screen.getByText("read client methods getSuspiciousTransactionLinkedAlertContext")).toBeInTheDocument();
  });

  it("WorkspaceDetailRouterUsesAlertReadOnlyContextPageForSuspiciousBridgeTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Dedicated read-only alert context true" })).toBeInTheDocument();
    expect(alertReadOnlyContextPageMock).toHaveBeenCalledTimes(1);
    expect(alertDetailsPageMock).not.toHaveBeenCalled();
  });

  it("WorkspaceDetailRouterDoesNotUseAlertDetailsPageForSuspiciousBridgeTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.queryByRole("heading", { name: /Alert detail/ })).not.toBeInTheDocument();
  });

  it("NormalAlertDetailStillUsesAlertDetailsPageTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId="alert-1"
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="fraudTransaction"
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Alert detail true" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /Dedicated read-only alert context/ })).not.toBeInTheDocument();
  });

  it("WorkspaceDetailRouterDoesNotPassReadOnlyContextToAlertDetailsPageForSuspiciousBridgeTest", () => {
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-1" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(alertDetailsPageMock).not.toHaveBeenCalled();
    expect(alertReadOnlyContextPageMock).toHaveBeenCalledTimes(1);
    expect(alertReadOnlyContextPageMock.mock.calls[0][0]).not.toHaveProperty("readOnlyContext");
  });

  it("SuspiciousBridgeDoesNotUseGetAlertForSourceStatesTest", () => {
    const missingSourceGetAlert = vi.fn();
    const loadingSourceGetAlert = vi.fn();
    const mismatchGetAlert = vi.fn();
    const commonProps = {
      selectedAlertId: null,
      selectedFraudCaseId: null,
      alertQueueState: undefined,
      session: { userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] },
      canReadAlerts: true,
      canReadFraudCases: false,
      workspacePage: "suspiciousTransactions",
      selectedLinkedAlertContext: true,
      selectedSuspiciousTransactionId: "suspicious-1",
      onCloseSelection: vi.fn(),
      onRefreshDashboard: vi.fn()
    };

    const { rerender } = render(
      <WorkspaceDetailRouter
        {...commonProps}
        apiClient={{ getAlert: missingSourceGetAlert, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        sourceSuspiciousTransaction={null}
      />
    );
    expect(missingSourceGetAlert).not.toHaveBeenCalled();

    rerender(
      <WorkspaceDetailRouter
        {...commonProps}
        apiClient={{ getAlert: loadingSourceGetAlert, submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        sourceSuspiciousTransaction={null}
        sourceSuspiciousTransactionLoading
      />
    );
    expect(loadingSourceGetAlert).not.toHaveBeenCalled();

    rerender(
      <WorkspaceDetailRouter
        {...commonProps}
        apiClient={{ getAlert: mismatchGetAlert, getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-other" }}
      />
    );
    expect(mismatchGetAlert).not.toHaveBeenCalled();
    expect(alertDetailsPageMock).not.toHaveBeenCalled();
  });

  it("sourceMissingStateDoesNotLogSuspiciousTransactionId", () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const consoleWarn = vi.spyOn(console, "warn").mockImplementation(() => {});
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-secret"
        sourceSuspiciousTransaction={null}
        sourceSuspiciousTransactionError={new Error("source failed suspicious-secret")}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByText("Linked alert context is unavailable.")).toBeInTheDocument();
    expect(JSON.stringify([...consoleError.mock.calls, ...consoleWarn.mock.calls])).not.toContain("suspicious-secret");
    consoleError.mockRestore();
    consoleWarn.mockRestore();
  });

  it("sourceLinkedAlertMismatchDoesNotLogAlertId", () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "alert-other" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Dedicated read-only alert context true" })).toBeInTheDocument();
    expect(JSON.stringify(consoleError.mock.calls)).not.toContain("alert-secret");
    consoleError.mockRestore();
  });

  it("sourceLinkedAlertMismatchDoesNotLogLinkedAlertId", () => {
    const consoleInfo = vi.spyOn(console, "info").mockImplementation(() => {});
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-1"
        sourceSuspiciousTransaction={{ suspiciousTransactionId: "suspicious-1", linkedAlertId: "linked-alert-secret" }}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Dedicated read-only alert context true" })).toBeInTheDocument();
    expect(JSON.stringify(consoleInfo.mock.calls)).not.toContain("linked-alert-secret");
    consoleInfo.mockRestore();
  });

  it("sourceReadinessStateDoesNotLogRawRoute", () => {
    const consoleLog = vi.spyOn(console, "log").mockImplementation(() => {});
    window.history.replaceState({}, "", "/?workspace=suspicious-transactions&suspiciousTransactionId=suspicious-secret&linkedAlertContext=1");
    render(
      <WorkspaceDetailRouter
        selectedAlertId={null}
        selectedFraudCaseId={null}
        alertQueueState={undefined}
        session={{ userId: "analyst-1", authorities: ["alert:read", "suspicious-transaction:read"] }}
        apiClient={{ getAlert: vi.fn(), getSuspiciousTransactionLinkedAlertContext: vi.fn(), submitAnalystDecision: vi.fn(), getAssistantSummary: vi.fn() }}
        canReadAlerts
        canReadFraudCases={false}
        workspacePage="suspiciousTransactions"
        selectedLinkedAlertContext
        selectedSuspiciousTransactionId="suspicious-secret"
        sourceSuspiciousTransaction={null}
        onCloseSelection={vi.fn()}
        onRefreshDashboard={vi.fn()}
      />
    );

    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(2);
    expect(JSON.stringify(consoleLog.mock.calls)).not.toContain("suspicious-secret");
    expect(JSON.stringify(consoleLog.mock.calls)).not.toContain("alert-secret");
    consoleLog.mockRestore();
  });

  it("passes fraud-case read capability and falls back to workspace heading focus", async () => {
    const onCloseSelection = vi.fn();
    render(
      <>
        <h2 tabIndex="-1" data-workspace-heading>Fraud Case Work Queue</h2>
        <WorkspaceDetailRouter
          selectedAlertId={null}
          selectedFraudCaseId="case-1"
          alertQueueState={{ page: { content: [] } }}
          session={{ userId: "analyst-1", authorities: [] }}
          apiClient={{}}
          canReadAlerts={false}
          canReadFraudCases
          workspacePage="analyst"
          onCloseSelection={onCloseSelection}
          onRefreshDashboard={vi.fn()}
        />
      </>
    );

    expect(screen.getByRole("heading", { name: "Fraud case detail true" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Back to list" }));
    expect(onCloseSelection).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole("heading", { name: "Fraud Case Work Queue" })).toHaveFocus());
  });

  it("restores focus to the originating fraud-case control", async () => {
    const onCloseSelection = vi.fn();
    render(
      <>
        <button type="button" data-detail-origin="fraud-case-case-1">Origin case</button>
        <WorkspaceDetailRouter
          selectedAlertId={null}
          selectedFraudCaseId="case-1"
          alertQueueState={{ page: { content: [] } }}
          session={{ userId: "analyst-1", authorities: [] }}
          apiClient={{}}
          canReadAlerts={false}
          canReadFraudCases
          workspacePage="analyst"
          onCloseSelection={onCloseSelection}
          onRefreshDashboard={vi.fn()}
        />
      </>
    );

    fireEvent.click(screen.getByRole("button", { name: "Back to list" }));

    expect(onCloseSelection).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole("button", { name: "Origin case" })).toHaveFocus());
  });

  it("restores focus for selector-sensitive alert IDs without throwing", async () => {
    const weirdAlertId = 'alert ] " \\ with spaces:1';
    const onCloseSelection = vi.fn();
    render(
      <>
        <button type="button" data-detail-origin={`alert-${weirdAlertId}`}>Origin weird alert</button>
        <WorkspaceDetailRouter
          selectedAlertId={weirdAlertId}
          selectedFraudCaseId={null}
          alertQueueState={{ page: { content: [{ alertId: weirdAlertId }] } }}
          session={{ userId: "analyst-1", authorities: [] }}
          apiClient={{}}
          canReadAlerts
          canReadFraudCases={false}
          workspacePage="fraudTransaction"
          onCloseSelection={onCloseSelection}
          onRefreshDashboard={vi.fn()}
        />
      </>
    );

    fireEvent.click(screen.getByRole("button", { name: "Back to list" }));

    expect(onCloseSelection).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole("button", { name: "Origin weird alert" })).toHaveFocus());
  });

  it("falls back to workspace heading when the alert origin disappears before back", async () => {
    const onCloseSelection = vi.fn();
    render(
      <>
        <button type="button" data-detail-origin="alert-alert-1">Origin alert</button>
        <h2 tabIndex="-1" data-workspace-heading>Alert review queue</h2>
        <WorkspaceDetailRouter
          selectedAlertId="alert-1"
          selectedFraudCaseId={null}
          alertQueueState={{ page: { content: [{ alertId: "alert-1" }] } }}
          session={{ userId: "analyst-1", authorities: [] }}
          apiClient={{}}
          canReadAlerts
          canReadFraudCases={false}
          workspacePage="fraudTransaction"
          onCloseSelection={onCloseSelection}
          onRefreshDashboard={vi.fn()}
        />
      </>
    );

    screen.getByRole("button", { name: "Origin alert" }).removeAttribute("data-detail-origin");
    fireEvent.click(screen.getByRole("button", { name: "Back to list" }));

    await waitFor(() => expect(screen.getByRole("heading", { name: "Alert review queue" })).toHaveFocus());
  });

  it("falls back safely for selector-sensitive missing origins", async () => {
    const weirdAlertId = 'alert ] " \\ with spaces:1';
    render(
      <>
        <h2 tabIndex="-1" data-workspace-heading>Alert review queue</h2>
        <WorkspaceDetailRouter
          selectedAlertId={weirdAlertId}
          selectedFraudCaseId={null}
          alertQueueState={{ page: { content: [{ alertId: weirdAlertId }] } }}
          session={{ userId: "analyst-1", authorities: [] }}
          apiClient={{}}
          canReadAlerts
          canReadFraudCases={false}
          workspacePage="fraudTransaction"
          onCloseSelection={vi.fn()}
          onRefreshDashboard={vi.fn()}
        />
      </>
    );

    fireEvent.click(screen.getByRole("button", { name: "Back to list" }));

    await waitFor(() => expect(screen.getByRole("heading", { name: "Alert review queue" })).toHaveFocus());
  });
});



