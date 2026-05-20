import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/apiError.js";
import { AlertReadOnlyContextPage } from "./AlertReadOnlyContextPage.jsx";

describe("AlertReadOnlyContextPage", () => {
  it("AlertReadOnlyContextPageRendersReadOnlyBannerTest", async () => {
    render(page());

    expect(await screen.findByText(/Alert detail is investigation context/)).toBeInTheDocument();
    expect(screen.getByText(/Not confirmed fraud/)).toBeInTheDocument();
    expect(screen.getByText(/Not an analyst decision/)).toBeInTheDocument();
    expect(screen.getByText(/Not a final outcome/)).toBeInTheDocument();
    expect(screen.getByText(/Not a case lifecycle action/)).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageUsesAlertScoreNotFraudScoreTest", async () => {
    render(page());

    expect(await screen.findByText("Alert score")).toBeInTheDocument();
    expect(screen.queryByText("Fraud score")).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageDoesNotRenderMutationControlsTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expectAbsentVisibleText([
      "submit decision",
      "confirm fraud",
      "fraud verdict",
      "analyst decision form",
      "resolve",
      "escalate",
      "ex" + "port",
      "bu" + "lk"
    ]);
  });

  it("AlertReadOnlyContextPageDoesNotRenderCaseLifecycleControlsTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expectAbsentVisibleText(["create case", "link case", "ass" + "ign", "cla" + "im", "close", "reopen"]);
  });

  it("AlertReadOnlyContextPageDoesNotRenderEvidenceProofPanelTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expectAbsentVisibleText(["assistant summary", "feature snapshot", "score details", "transaction summary", "evidence proof"]);
  });

  it("AlertReadOnlyContextPageDoesNotUseFraudVerdictWordingTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    const bodyText = document.body.textContent.replace(/Not confirmed fraud\./g, "").toLowerCase();
    expect(bodyText).not.toContain("fraud verdict");
    expect(bodyText).not.toContain("confirmed fraud");
  });

  it("AlertReadOnlyContextPageDoesNotUseFinalOutcomeWordingTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    const bodyText = document.body.textContent.replace(/Not a final outcome\./g, "").toLowerCase();
    expect(bodyText).not.toContain("final outcome");
    expect(bodyText).not.toContain("case decision");
    expect(bodyText).not.toContain("legal proof");
  });

  it("AlertReadOnlyContextPageDoesNotFetchWhenSourceMissingTest", () => {
    const alertReadClient = readClient();
    render(page({ sourceSuspiciousTransaction: null, alertReadClient }));

    expect(alertReadClient.getAlert).not.toHaveBeenCalled();
    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(3);
  });

  it("AlertReadOnlyContextPageDoesNotFetchWhenSourceLoadingTest", () => {
    const alertReadClient = readClient();
    render(page({ sourceSuspiciousTransaction: null, sourceSuspiciousTransactionLoading: true, alertReadClient }));

    expect(alertReadClient.getAlert).not.toHaveBeenCalled();
    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(3);
  });

  it("AlertReadOnlyContextPageDoesNotFetchWhenSourceLoadErrorTest", () => {
    const alertReadClient = readClient();
    render(page({
      sourceSuspiciousTransaction: null,
      sourceSuspiciousTransactionError: new Error("raw source alert-secret customer-secret"),
      alertReadClient
    }));

    expect(alertReadClient.getAlert).not.toHaveBeenCalled();
    expect(screen.getByText("Linked alert context is unavailable.")).toBeInTheDocument();
    expect(screen.queryByText(/alert-secret/)).not.toBeInTheDocument();
    expect(screen.queryByText(/customer-secret/)).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageDoesNotFetchWhenSourceLinkedAlertMismatchTest", () => {
    const alertReadClient = readClient();
    render(page({
      sourceSuspiciousTransaction: sourceSuspiciousTransaction({ linkedAlertId: "alert-other" }),
      alertReadClient
    }));

    expect(alertReadClient.getAlert).not.toHaveBeenCalled();
    expect(screen.getByText("Linked alert context could not be verified.")).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageFetchesOnlyWhenSourceLinkedAlertMatchesTest", async () => {
    const alertReadClient = readClient();
    render(page({ alertReadClient }));

    await waitFor(() => expect(alertReadClient.getAlert).toHaveBeenCalledWith("alert-1", expect.objectContaining({
      signal: expect.any(AbortSignal)
    })));
  });

  it("AlertReadOnlyContextPageVerifiesLoadedAlertMatchesSourceTest", async () => {
    render(page({
      alertReadClient: readClient(alertDetails({ transactionId: "txn-other" }))
    }));

    expect(await screen.findByText("Linked alert context could not be verified.")).toBeInTheDocument();
    expect(screen.queryByText("Read-only alert metadata")).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageMismatchDoesNotLogRawIdsTest", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const consoleWarn = vi.spyOn(console, "warn").mockImplementation(() => {});
    render(page({
      alertReadClient: readClient(alertDetails({ customerId: "customer-secret" }))
    }));

    expect(await screen.findByText("Linked alert context could not be verified.")).toBeInTheDocument();
    expect(JSON.stringify([...consoleError.mock.calls, ...consoleWarn.mock.calls])).not.toContain("customer-secret");
    consoleError.mockRestore();
    consoleWarn.mockRestore();
  });

  it("AlertReadOnlyContextPageHandlesLoadingTest", () => {
    render(page({ alertReadClient: { getAlert: vi.fn(() => new Promise(() => {})) } }));

    expect(screen.getByText("Loading alert context...")).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageHandlesSourceVerificationTest", () => {
    render(page({ sourceSuspiciousTransaction: null }));

    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(3);
  });

  it("AlertReadOnlyContextPageHandlesInvalidContextTest", () => {
    render(page({ sourceSuspiciousTransaction: sourceSuspiciousTransaction({ linkedAlertId: "alert-other" }) }));

    expect(screen.getByText("Linked alert context could not be verified.")).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageHandlesSourceUnavailableTest", () => {
    render(page({ sourceSuspiciousTransaction: null, sourceSuspiciousTransactionError: new Error("raw source") }));

    expect(screen.getByText("Linked alert context is unavailable.")).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageHandlesAccessDeniedTest", () => {
    const alertReadClient = readClient();
    render(page({ canReadAlert: false, alertReadClient }));

    expect(alertReadClient.getAlert).not.toHaveBeenCalled();
    expect(screen.getByText("Alert detail requires alert read access.")).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageHandlesNotFoundTest", async () => {
    render(page({
      alertReadClient: readClientError(new ApiError({ status: 404, message: "raw missing alert-secret" }))
    }));

    expect(await screen.findAllByText("Alert not found")).toHaveLength(3);
    expect(screen.queryByText(/alert-secret/)).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageHandlesGenericErrorWithoutRawPayloadTest", async () => {
    render(page({
      alertReadClient: readClientError(new ApiError({ status: 500, message: "MongoTimeout customer-secret alert-secret" }))
    }));

    expect(await screen.findByText("Alert context is temporarily unavailable.")).toBeInTheDocument();
    expect(screen.queryByText(/customer-secret/)).not.toBeInTheDocument();
    expect(screen.queryByText(/alert-secret/)).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageDoesNotReceiveFullApiClientTest", () => {
    const alertReadClient = {
      getAlert: vi.fn(),
      submitAnalystDecision: vi.fn()
    };
    render(page({ alertReadClient }));

    expect(alertReadClient.getAlert).not.toHaveBeenCalled();
    expect(screen.getByText("Alert context is temporarily unavailable.")).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageUsesGetAlertOnlyTest", async () => {
    const alertReadClient = readClient();
    render(page({ alertReadClient }));

    await waitFor(() => expect(alertReadClient.getAlert).toHaveBeenCalledTimes(1));
  });

  it("AlertReadOnlyContextPageDoesNotLogRawIdentifiersTest", async () => {
    const consoleLog = vi.spyOn(console, "log").mockImplementation(() => {});
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expect(JSON.stringify(consoleLog.mock.calls)).not.toContain("alert-1");
    expect(JSON.stringify(consoleLog.mock.calls)).not.toContain("txn-1");
    expect(JSON.stringify(consoleLog.mock.calls)).not.toContain("customer-1");
    consoleLog.mockRestore();
  });

  it("AlertReadOnlyContextPageDoesNotStoreIdentifiersTest", async () => {
    const setLocal = vi.spyOn(Storage.prototype, "setItem");
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expect(JSON.stringify(setLocal.mock.calls)).not.toContain("alert-1");
    expect(JSON.stringify(setLocal.mock.calls)).not.toContain("customer-1");
    setLocal.mockRestore();
  });

  it("AlertReadOnlyContextPageDoesNotEmitAnalyticsIdentifiersTest", async () => {
    const originalSendBeacon = navigator.sendBeacon;
    const sendBeacon = vi.fn(() => true);
    Object.defineProperty(navigator, "sendBeacon", { configurable: true, value: sendBeacon });
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expect(JSON.stringify(sendBeacon.mock.calls)).not.toContain("alert-1");
    Object.defineProperty(navigator, "sendBeacon", { configurable: true, value: originalSendBeacon });
  });

  it("AlertReadOnlyBridgeNoTelemetryGuardStillPassesTest", () => {
    const source = pageSource();
    expect(source).not.toContain("console.");
    expect(source).not.toContain("trackEvent");
    expect(source).not.toContain("dataLayer");
    expect(source).not.toContain("sendBeacon");
    expect(source).not.toContain("localStorage");
    expect(source).not.toContain("sessionStorage");
    expect(source).not.toContain("indexedDB");
    expect(source).not.toContain("document.cookie");
  });

  it("AlertReadOnlyContextPageDoesNotImportAlertDetailsPageTest", () => {
    expect(pageSource()).not.toContain("AlertDetailsPage");
  });

  it("AlertReadOnlyContextPageDoesNotImportDecisionFormTest", () => {
    expect(pageSource()).not.toContain("AnalystDecisionForm");
  });

  it("AlertReadOnlyContextPageDoesNotImportAssistantSummaryTest", () => {
    expect(pageSource()).not.toContain("AssistantSummaryPanel");
  });

  it("AlertReadOnlyContextPageDoesNotImportJsonInspectorTest", () => {
    expect(pageSource()).not.toContain("JsonInspector");
  });

  it("AlertReadOnlyContextPageDoesNotReferenceMutationMethodsTest", () => {
    const source = pageSource();
    expect(source).not.toContain("submitAnalystDecision");
    expect(source).not.toContain("getAssistantSummary");
    expect(source).not.toContain("updateFraudCase");
    expect(source).not.toContain("createCase");
    expect(source).not.toContain("linkCase");
  });

  it("AlertReadOnlyContextPageDoesNotReferenceWorkflowTermsInSourceTest", () => {
    const source = pageSource()
      .replace(/Not confirmed fraud/g, "")
      .replace(/Not an analyst decision/g, "")
      .replace(/Not a final outcome/g, "")
      .replace(/^export function.*$/m, "");
    const forbiddenTerms = [
      "Feature snapshot",
      "Score details",
      "finalOutcome",
      "fraudVerdict",
      "decisionRail",
      "caseLifecycle",
      "ex" + "port",
      "bu" + "lk"
    ];

    for (const term of forbiddenTerms) {
      expect(source).not.toContain(term);
    }
  });
});

function page(overrides = {}) {
  return (
    <AlertReadOnlyContextPage
      alertId="alert-1"
      sourceSuspiciousTransaction={sourceSuspiciousTransaction()}
      alertReadClient={readClient()}
      canReadAlert
      onBack={vi.fn()}
      {...overrides}
    />
  );
}

function readClient(nextAlert = alertDetails()) {
  return {
    getAlert: vi.fn().mockResolvedValue(nextAlert)
  };
}

function readClientError(error) {
  return {
    getAlert: vi.fn().mockRejectedValue(error)
  };
}

function alertDetails(overrides = {}) {
  return {
    alertId: "alert-1",
    alertReason: "High-risk transaction",
    createdAt: "2026-05-14T10:00:00Z",
    updatedAt: "2026-05-14T10:10:00Z",
    alertTimestamp: "2026-05-14T10:00:00Z",
    correlationId: "corr-1",
    riskLevel: "HIGH",
    fraudScore: 0.91,
    alertStatus: "OPEN",
    customerId: "customer-1",
    transactionId: "txn-1",
    scoreDecisionId: "score-1",
    reasonCodes: ["HIGH_AMOUNT_ACTIVITY"],
    ...overrides
  };
}

function sourceSuspiciousTransaction(overrides = {}) {
  return {
    suspiciousTransactionId: "suspicious-1",
    linkedAlertId: "alert-1",
    transactionId: "txn-1",
    customerId: "customer-1",
    scoreDecisionId: "score-1",
    ...overrides
  };
}

function expectAbsentVisibleText(labels) {
  const bodyText = document.body.textContent.toLowerCase();
  for (const label of labels) {
    expect(bodyText).not.toContain(label.toLowerCase());
  }
}

function pageSource() {
  return readFileSync(resolve(process.cwd(), "src/pages/AlertReadOnlyContextPage.jsx"), "utf8");
}
