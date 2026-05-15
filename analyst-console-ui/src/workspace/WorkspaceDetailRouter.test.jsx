import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("../pages/AlertDetailsPage.jsx", () => ({
  AlertDetailsPage: ({ canReadAlert, workspaceLabel, alertSummaryRuntimeState, onBack }) => (
    <section>
      <h2>Alert detail {String(canReadAlert)}</h2>
      <span>{workspaceLabel}</span>
      <span>{alertSummaryRuntimeState}</span>
      <button type="button" onClick={onBack}>Back to list</button>
    </section>
  )
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
