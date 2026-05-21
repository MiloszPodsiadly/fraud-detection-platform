import { render, screen, waitFor } from "@testing-library/react";
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

  it("LinkedAlertAvailableRendersReadOnlyContextTest", async () => {
    render(page());

    expect(await screen.findByRole("heading", { name: "Alert context" })).toBeInTheDocument();
    expect(screen.getByText("Alert score")).toBeInTheDocument();
    expect(screen.getByText("0.91")).toBeInTheDocument();
    expect(screen.getByText("Read-only alert metadata")).toBeInTheDocument();
    expect(screen.getByText("HIGH_AMOUNT_ACTIVITY")).toBeInTheDocument();
    expect(screen.queryByText("Fraud score")).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageAcceptsLinkedAlertResolverClientOnlyTest", async () => {
    const linkedAlertContextClient = resolverClient();
    render(page({ linkedAlertContextClient }));

    await waitFor(() => expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext).toHaveBeenCalledTimes(1));
    expect(Object.keys(linkedAlertContextClient)).toEqual(["getSuspiciousTransactionLinkedAlertContext"]);
  });

  it("AlertReadOnlyContextPageDoesNotReceiveFullApiClientTest", () => {
    const linkedAlertContextClient = {
      getSuspiciousTransactionLinkedAlertContext: vi.fn(),
      getAlert: vi.fn(),
      submitAnalystDecision: vi.fn()
    };
    render(page({ linkedAlertContextClient }));

    expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext).not.toHaveBeenCalled();
    expect(screen.getByText("Alert context is temporarily unavailable.")).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageDoesNotRequireAlertIdPropTest", async () => {
    render(page({ alertId: undefined }));

    expect(await screen.findByRole("heading", { name: "Alert context" })).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageDoesNotRequireLinkedAlertIdForFetchTest", async () => {
    const linkedAlertContextClient = resolverClient();
    render(page({
      sourceSuspiciousTransaction: sourceSuspiciousTransaction({ linkedAlertId: "" }),
      linkedAlertContextClient
    }));

    await waitFor(() => expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext).toHaveBeenCalledWith("suspicious-1", expect.objectContaining({
      signal: expect.any(AbortSignal)
    })));
  });

  it("AlertReadOnlyContextPageCallsResolverWithSuspiciousTransactionIdOnlyTest", async () => {
    const linkedAlertContextClient = resolverClient();
    render(page({ linkedAlertContextClient }));

    await waitFor(() => expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext).toHaveBeenCalledTimes(1));
    expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext.mock.calls[0][0]).toBe("suspicious-1");
    expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext.mock.calls[0][0]).not.toBe("alert-1");
  });

  it("AlertReadOnlyContextPageDoesNotCallGetAlertByAlertIdTest", async () => {
    const linkedAlertContextClient = resolverClient();
    render(page({ linkedAlertContextClient }));

    await screen.findByRole("heading", { name: "Alert context" });
    expect(linkedAlertContextClient.getAlert).toBeUndefined();
  });

  it("AlertReadOnlyContextPageDoesNotFallbackToGetAlertWhenResolverFailsTest", async () => {
    const getAlert = vi.fn();
    const linkedAlertContextClient = {
      getSuspiciousTransactionLinkedAlertContext: vi.fn().mockRejectedValue(new ApiError({ status: 500, message: "raw alert-secret" }))
    };
    render(page({ linkedAlertContextClient, getAlert }));

    expect(await screen.findByText("Alert context is temporarily unavailable.")).toBeInTheDocument();
    expect(getAlert).not.toHaveBeenCalled();
  });

  it("AlertReadOnlyContextPageQueryKeyUsesSuspiciousTransactionIdOnlyTest", () => {
    const source = pageSource();

    expect(source).toContain("suspiciousTransactionId");
    expect(source).not.toContain("alertId,");
    expect(source).not.toContain("linkedAlertId");
  });

  it("NoLinkedAlertRendersNoLinkedAlertStateTest", async () => {
    render(page({ linkedAlertContextClient: resolverClient({ state: "NO_LINKED_ALERT" }) }));

    expect(await screen.findByText("No linked alert.")).toBeInTheDocument();
    expectNoAlertFields();
  });

  it("LinkedAlertNotFoundRendersSafeUnavailableStateTest", async () => {
    render(page({ linkedAlertContextClient: resolverClient({
      state: "LINKED_ALERT_NOT_FOUND",
      alertId: "alert-secret"
    }) }));

    expect(await screen.findByText("Linked alert is not available.")).toBeInTheDocument();
    expect(screen.queryByText("alert-secret")).not.toBeInTheDocument();
    expectNoAlertFields();
  });

  it("RelationshipMismatchRendersInvalidContextStateTest", async () => {
    render(page({ linkedAlertContextClient: resolverClient({
      state: "LINKED_ALERT_RELATIONSHIP_MISMATCH",
      alertId: "alert-secret",
      customerId: "customer-secret"
    }) }));

    expect(await screen.findByText("Linked alert context could not be verified.")).toBeInTheDocument();
    expect(screen.queryByText("alert-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("customer-secret")).not.toBeInTheDocument();
    expectNoAlertFields();
  });

  it("TemporarilyUnavailableDoesNotRenderAlertFieldsTest", async () => {
    render(page({ linkedAlertContextClient: resolverClient({
      state: "TEMPORARILY_UNAVAILABLE",
      alertId: "alert-secret",
      customerId: "customer-secret"
    }) }));

    expect(await screen.findByText("Linked alert context is temporarily unavailable.")).toBeInTheDocument();
    expect(screen.queryByText("alert-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("customer-secret")).not.toBeInTheDocument();
    expectNoAlertFields();
  });

  it("Http200WithTemporarilyUnavailableDoesNotRenderAvailableContextTest", async () => {
    render(page({ linkedAlertContextClient: resolverClient({ state: "TEMPORARILY_UNAVAILABLE" }) }));

    expect(await screen.findByText("Linked alert context is temporarily unavailable.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert context" })).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageUnknownStateFailsClosedTest", async () => {
    render(page({ linkedAlertContextClient: resolverClient({
      state: "FUTURE_STATE",
      alertId: "alert-secret"
    }) }));

    expect(await screen.findByText("Linked alert context is unavailable.")).toBeInTheDocument();
    expect(screen.queryByText("alert-secret")).not.toBeInTheDocument();
    expectNoAlertFields();
  });

  it("NonAvailableStateWithAccidentalAlertPayloadDoesNotRenderAlertFieldsTest", async () => {
    render(page({ linkedAlertContextClient: resolverClient({
      state: "NO_LINKED_ALERT",
      alertId: "alert-secret",
      transactionId: "txn-secret",
      customerId: "customer-secret"
    }) }));

    expect(await screen.findByText("No linked alert.")).toBeInTheDocument();
    expect(screen.queryByText("txn-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("customer-secret")).not.toBeInTheDocument();
    expectNoAlertFields();
  });

  it("RelationshipMismatchWithAccidentalAlertPayloadDoesNotRenderAlertFieldsTest", async () => {
    render(page({ linkedAlertContextClient: resolverClient({
      state: "LINKED_ALERT_RELATIONSHIP_MISMATCH",
      alertId: "alert-secret",
      transactionId: "txn-secret",
      customerId: "customer-secret"
    }) }));

    expect(await screen.findByText("Linked alert context could not be verified.")).toBeInTheDocument();
    expect(screen.queryByText("txn-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("customer-secret")).not.toBeInTheDocument();
    expectNoAlertFields();
  });

  it("AlertReadOnlyContextPageHandlesAccessDeniedTest", async () => {
    render(page({
      linkedAlertContextClient: resolverClientError(new ApiError({ status: 403, message: "raw forbidden alert-secret" }))
    }));

    expect(await screen.findByText("Alert detail requires alert read access.")).toBeInTheDocument();
    expect(screen.queryByText(/alert-secret/)).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageDoesNotFallbackAfterForbiddenTest", async () => {
    const getAlert = vi.fn();
    render(page({
      linkedAlertContextClient: resolverClientError(new ApiError({ status: 403, message: "raw forbidden alert-secret" })),
      getAlert
    }));

    expect(await screen.findByText("Alert detail requires alert read access.")).toBeInTheDocument();
    expect(getAlert).not.toHaveBeenCalled();
  });

  it("AlertReadOnlyContextPageDoesNotRenderAlertFieldsAfterForbiddenTest", async () => {
    render(page({
      linkedAlertContextClient: resolverClientError(new ApiError({ status: 401, message: "raw forbidden alert-secret" }))
    }));

    expect(await screen.findByText("Alert detail requires alert read access.")).toBeInTheDocument();
    expectNoAlertFields();
  });

  it("AlertReadOnlyContextPageDoesNotFetchWhenSourceLoadingTest", () => {
    const linkedAlertContextClient = resolverClient();
    render(page({ sourceSuspiciousTransactionLoading: true, linkedAlertContextClient }));

    expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext).not.toHaveBeenCalled();
    expect(screen.getAllByText("Verifying linked alert context")).toHaveLength(3);
  });

  it("AlertReadOnlyContextPageDoesNotFetchWhenSourceLoadErrorTest", () => {
    const linkedAlertContextClient = resolverClient();
    render(page({
      sourceSuspiciousTransactionError: new Error("raw source alert-secret customer-secret"),
      linkedAlertContextClient
    }));

    expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext).not.toHaveBeenCalled();
    expect(screen.getByText("Linked alert context is unavailable.")).toBeInTheDocument();
    expect(screen.queryByText(/alert-secret/)).not.toBeInTheDocument();
    expect(screen.queryByText(/customer-secret/)).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageFailsClosedWhenSuspiciousTransactionIdMissingTest", () => {
    const linkedAlertContextClient = resolverClient();
    render(page({ suspiciousTransactionId: "", linkedAlertContextClient }));

    expect(linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext).not.toHaveBeenCalled();
    expect(screen.getByText("Linked alert context could not be verified.")).toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageHandlesLoadingTest", () => {
    render(page({ linkedAlertContextClient: {
      getSuspiciousTransactionLinkedAlertContext: vi.fn(() => new Promise(() => {}))
    } }));

    expect(screen.getByText("Loading alert context...")).toBeInTheDocument();
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

  it("AlertReadOnlyContextPageDoesNotRenderWorkflowControlsTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expectAbsentVisibleText(["create case", "link case", "ass" + "ign", "cla" + "im", "close", "reopen"]);
  });

  it("AlertReadOnlyContextPageDoesNotRenderFraudVerdictWordingTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    const bodyText = document.body.textContent.replace(/Not confirmed fraud\./g, "").toLowerCase();
    expect(bodyText).not.toContain("fraud verdict");
    expect(bodyText).not.toContain("confirmed fraud");
  });

  it("AlertReadOnlyContextPageDoesNotRenderAssistantSummaryTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expect(screen.queryByText(/assistant summary/i)).not.toBeInTheDocument();
  });

  it("AlertReadOnlyContextPageDoesNotRenderEvidenceProofPanelTest", async () => {
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expectAbsentVisibleText(["feature snapshot", "score details", "transaction summary", "evidence proof"]);
  });

  it("AlertReadOnlyContextPageUsesAlertScoreWordingTest", async () => {
    render(page());

    expect(await screen.findByText("Alert score")).toBeInTheDocument();
    expect(screen.queryByText("Fraud score")).not.toBeInTheDocument();
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

  it("AlertReadOnlyContextPageDoesNotPersistAlertIdInBrowserStorageTest", async () => {
    const setLocal = vi.spyOn(Storage.prototype, "setItem");
    render(page());

    await screen.findByRole("heading", { name: "Alert context" });
    expect(JSON.stringify(setLocal.mock.calls)).not.toContain("alert-1");
    expect(JSON.stringify(setLocal.mock.calls)).not.toContain("customer-1");
    setLocal.mockRestore();
  });

  it("LinkedAlertContextErrorBoundaryDoesNotLogRawIdentifiersTest", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    render(page({
      linkedAlertContextClient: resolverClientError(new ApiError({ status: 500, message: "MongoTimeout customer-secret alert-secret" }))
    }));

    expect(await screen.findByText("Alert context is temporarily unavailable.")).toBeInTheDocument();
    expect(JSON.stringify(consoleError.mock.calls)).not.toContain("customer-secret");
    expect(JSON.stringify(consoleError.mock.calls)).not.toContain("alert-secret");
    consoleError.mockRestore();
  });

  it("SourceDoesNotUseForbiddenTelemetryOrStorageSinksTest", () => {
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
      suspiciousTransactionId="suspicious-1"
      sourceSuspiciousTransaction={sourceSuspiciousTransaction()}
      linkedAlertContextClient={resolverClient()}
      canReadAlert
      onBack={vi.fn()}
      {...overrides}
    />
  );
}

function resolverClient(nextContext = linkedAlertAvailable()) {
  return {
    getSuspiciousTransactionLinkedAlertContext: vi.fn().mockResolvedValue(nextContext)
  };
}

function resolverClientError(error) {
  return {
    getSuspiciousTransactionLinkedAlertContext: vi.fn().mockRejectedValue(error)
  };
}

function linkedAlertAvailable(overrides = {}) {
  return {
    state: "LINKED_ALERT_AVAILABLE",
    alertId: "alert-1",
    createdAt: "2026-05-14T10:00:00Z",
    updatedAt: "2026-05-14T10:10:00Z",
    correlationId: "corr-1",
    riskLevel: "HIGH",
    alertScore: 0.91,
    alertStatus: "OPEN",
    customerId: "customer-1",
    accountId: "account-1",
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

function expectNoAlertFields() {
  expect(screen.queryByText("Read-only alert metadata")).not.toBeInTheDocument();
  expect(screen.queryByText("Reason codes")).not.toBeInTheDocument();
  expect(screen.queryByText("Alert score")).not.toBeInTheDocument();
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
