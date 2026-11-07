import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { TransactionRiskIntelligencePanel } from "./TransactionRiskIntelligencePanel.jsx";
import { useScoredTransactionDetail } from "../transactions/useScoredTransactionDetail.js";
import { useFraudFeedback } from "../transactions/useFraudFeedback.js";
import { transactionRiskIntelligencePanelId } from "../transactions/transactionRiskIntelligencePanelId.js";
import {
  absentDetail,
  availableDetail,
  comparisonNullDetail,
  degradedDetail,
  emptyArraysDetail,
  invalidResponseError,
  networkError,
  notFoundError,
  permissionDeniedError,
  unavailableDetail
} from "../transactions/transactionRiskIntelligenceFixtures.js";

vi.mock("../transactions/useScoredTransactionDetail.js", () => ({
  useScoredTransactionDetail: vi.fn()
}));

vi.mock("../transactions/useFraudFeedback.js", () => ({
  useFraudFeedback: vi.fn()
}));

describe("TransactionRiskIntelligencePanel", () => {
  beforeEach(() => {
    useScoredTransactionDetail.mockReturnValue({ detail: availableDetail(), isLoading: false, error: null });
    useFraudFeedback.mockReturnValue({
      feedback: null,
      isLoading: false,
      error: null,
      canReadFeedback: true,
      canCreateFeedback: true,
      submitState: "idle",
      submitError: null,
      submit: vi.fn()
    });
  });

  it("renders header and persistent diagnostic boundary banner", () => {
    renderPanel();

    expect(screen.getByRole("heading", { name: "Transaction Risk Intelligence" })).toBeInTheDocument();
    const boundary = screen.getByRole("region", { name: "Diagnostic Boundary" });
    expect(boundary).toHaveTextContent("Transaction diagnostics are read-only.");
    expect(boundary).toHaveTextContent("Analyst Feedback is a separate bounded review-outcome record.");
    expect(boundary).not.toHaveTextContent("read-only diagnostic view");
    expect(boundary).toHaveTextContent("It does not approve");
    expect(boundary).toHaveTextContent("authorize payment");
    expect(boundary).toHaveTextContent("create cases");
    expect(boundary).toHaveTextContent("Velocity score is a deterministic normalized risk-severity signal.");
    expect(boundary).toHaveTextContent("It is not a calibrated fraud probability");
    expect(boundary).toHaveTextContent("must not be interpreted as model confidence");
  });

  it("uses a stable sanitized panel id", () => {
    renderPanel({ transactionId: "txn/unsafe id:1" });

    expect(screen.getByRole("region", { name: "Transaction Risk Intelligence" })).toHaveAttribute(
      "id",
      "transaction-risk-intelligence-txn-unsafe-id-1"
    );
    expect(transactionRiskIntelligencePanelId("txn/unsafe id:1")).toBe("transaction-risk-intelligence-txn-unsafe-id-1");
  });

  it("renders transaction summary and alertRecommended safe wording", () => {
    renderPanel();

    const summary = screen.getByRole("region", { name: "Transaction Summary" });
    expect(within(summary).getByText("txn-available-1")).toBeInTheDocument();
    expect(within(summary).getByText("corr-available-1")).toBeInTheDocument();
    expect(within(summary).getByText("0.91")).toBeInTheDocument();
    expect(within(summary).getByText("Alert recommendation flag")).toBeInTheDocument();
    expect(within(summary).getByText("Review recommended")).toBeInTheDocument();
    expect(screen.getByText("alertRecommended is a scored transaction field, not a final payment decision.")).toBeInTheDocument();
  });

  it("renders missing transaction timestamps as unavailable", () => {
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({ transactionTimestamp: null, scoredAt: null }),
      isLoading: false,
      error: null
    });

    renderPanel();

    const summary = screen.getByRole("region", { name: "Transaction Summary" });
    expect(within(summary).getAllByText("Not available")).toHaveLength(2);
  });

  it.each([
    ["true", true, "Review recommended", false],
    ["false", false, "No review recommendation", false],
    ["null", null, "Unknown", false],
    ["undefined", undefined, "Unknown", false],
    ["missing", undefined, "Unknown", true]
  ])("renders the same alert recommendation policy for %s in detail", (_label, alertRecommended, expected, omit) => {
    const detail = availableDetail();
    if (omit) {
      delete detail.alertRecommended;
    } else {
      detail.alertRecommended = alertRecommended;
    }
    useScoredTransactionDetail.mockReturnValue({
      detail,
      isLoading: false,
      error: null
    });

    renderPanel();

    expect(within(screen.getByRole("region", { name: "Transaction Summary" })).getByText(expected)).toBeInTheDocument();
  });

  it("renders transaction merchant context separately from verified Merchant Risk history", () => {
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({
        merchantInfo: {
          merchantName: "Northwind Books",
          merchantId: "merchant-17",
          merchantCategoryCode: "5732",
          merchantCategory: "Books",
          channel: "ECOMMERCE",
          acquiringCountryCode: "PL",
          attributes: { privateMapValue: "must-not-render" }
        }
      }),
      isLoading: false,
      error: null
    });

    renderPanel();

    const merchant = screen.getByRole("region", { name: "Merchant Investigation" });
    expect(within(merchant).getByText("Merchant name")).toBeInTheDocument();
    expect(within(merchant).getByText("Northwind Books")).toBeInTheDocument();
    expect(within(merchant).getByText("merchant-17")).toBeInTheDocument();
    expect(within(merchant).getByText("5732")).toBeInTheDocument();
    expect(within(merchant).getByText("Books")).toBeInTheDocument();
    expect(within(merchant).getByText("ECOMMERCE")).toBeInTheDocument();
    expect(within(merchant).getByText("PL")).toBeInTheDocument();
    expect(merchant).toHaveTextContent("context only");
    expect(merchant).toHaveTextContent("Verified Merchant Risk history is not provided by this transaction detail.");
    expect(merchant).toHaveTextContent("Absence of evidence is not evidence of low risk.");
    expect(merchant).not.toHaveTextContent("must-not-render");
    expect(merchant).not.toHaveTextContent(/score|percentage|first-ever|prior observations/i);
  });

  it("trims a safe merchant name for transaction detail display", () => {
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({ merchantInfo: { merchantName: "  Northwind Books  " } }),
      isLoading: false,
      error: null
    });

    renderPanel();

    expect(within(screen.getByRole("region", { name: "Merchant Investigation" }))
      .getByText("Northwind Books")).toBeInTheDocument();
  });

  it("requires an explicit review when the expanded row reports a new scoring occurrence", () => {
    const originalToken = "a".repeat(64);
    const refreshedToken = "b".repeat(64);
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({ scoringOccurrenceToken: originalToken }),
      isLoading: false,
      error: null
    });
    const { rerender } = renderPanel({ scoringOccurrenceToken: originalToken });

    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({ scoringOccurrenceToken: refreshedToken }),
      isLoading: false,
      error: null
    });
    rerender(<TransactionRiskIntelligencePanel
      transactionId="txn-available-1"
      scoringOccurrenceToken={refreshedToken}
      apiClient={{}}
    />);

    expect(screen.getByRole("status")).toHaveTextContent("Scoring changed while this transaction was expanded.");
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "I reviewed the current scoring" }));
    expect(screen.getByRole("button", { name: "Record feedback" })).toBeInTheDocument();
    expect(useScoredTransactionDetail).toHaveBeenLastCalledWith(expect.objectContaining({
      expectedScoringOccurrenceToken: refreshedToken
    }));
  });

  it("keeps same-occurrence feedback drafts and clears them after a new occurrence loads", () => {
    const originalToken = "a".repeat(64);
    const refreshedToken = "b".repeat(64);
    const apiClient = {};
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({ scoringOccurrenceToken: originalToken }),
      isLoading: false,
      error: null
    });
    const { rerender } = renderPanel({ scoringOccurrenceToken: originalToken, apiClient });
    fireEvent.click(screen.getByLabelText("Mark as confirmed legitimate"));
    fireEvent.change(screen.getByLabelText("Notes"), { target: { value: "Review notes for occurrence A" } });

    rerender(<TransactionRiskIntelligencePanel
      transactionId="txn-available-1"
      scoringOccurrenceToken={originalToken}
      apiClient={apiClient}
    />);

    expect(screen.getByLabelText("Mark as confirmed legitimate")).toBeChecked();
    expect(screen.getByLabelText("Notes")).toHaveValue("Review notes for occurrence A");

    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: true, error: null });
    rerender(<TransactionRiskIntelligencePanel
      transactionId="txn-available-1"
      scoringOccurrenceToken={refreshedToken}
      apiClient={apiClient}
    />);
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({ scoringOccurrenceToken: refreshedToken }),
      isLoading: false,
      error: null
    });
    rerender(<TransactionRiskIntelligencePanel
      transactionId="txn-available-1"
      scoringOccurrenceToken={refreshedToken}
      apiClient={apiClient}
    />);

    fireEvent.click(screen.getByRole("button", { name: "I reviewed the current scoring" }));
    expect(screen.getByLabelText("Mark as confirmed fraud")).toBeChecked();
    expect(screen.getByLabelText("Notes")).toHaveValue("");
  });

  it("shows a separate occurrence-changed error and refreshes the workspace list", () => {
    const refreshTransactions = vi.fn();
    useScoredTransactionDetail.mockReturnValue({
      detail: null,
      isLoading: false,
      error: { code: "SCORING_OCCURRENCE_CHANGED" }
    });

    renderPanel({ onRefreshTransactions: refreshTransactions });

    expect(screen.getByRole("alert")).toHaveTextContent("The scored transaction changed after this row was loaded.");
    fireEvent.click(screen.getByRole("button", { name: "Refresh scored transactions" }));
    expect(refreshTransactions).toHaveBeenCalledTimes(1);
  });

  it("keeps the evidence limitation when merchant details are absent", () => {
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({ merchantInfo: null }),
      isLoading: false,
      error: null
    });

    renderPanel();

    const merchant = screen.getByRole("region", { name: "Merchant Investigation" });
    expect(merchant).toHaveTextContent("No displayable transaction merchant details are available.");
    expect(merchant).toHaveTextContent("Verified Merchant Risk history is not provided by this transaction detail.");
  });

  it("omits malformed and unsafe merchant values while rendering HTML-looking text safely", () => {
    const longMerchantId = "m".repeat(129);
    const controlValue = "North\u0000wind";
    const bidiValue = "North\u202ewind";
    const htmlLookingName = "<script>merchant()</script>";
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({
        merchantInfo: {
          merchantName: htmlLookingName,
          merchantId: longMerchantId,
          merchantCategoryCode: controlValue,
          merchantCategory: bidiValue,
          channel: { label: "ONLINE" },
          acquiringCountryCode: ["PL"],
          attributes: {
            nestedPii: { email: "private@example.test" },
            tokenHint: "hidden-token",
            unbounded: "x".repeat(10_000),
            html: "<img src=x onerror=alert(1)>"
          }
        }
      }),
      isLoading: false,
      error: null
    });

    const { container } = renderPanel();

    const merchant = screen.getByRole("region", { name: "Merchant Investigation" });
    expect(within(merchant).getByText(htmlLookingName)).toBeInTheDocument();
    expect(merchant.querySelector("script")).toBeNull();
    expect(merchant.querySelector("img")).toBeNull();
    expect(merchant).not.toHaveTextContent("Not supplied.");
    expect(container.textContent).not.toContain(longMerchantId);
    expect(container.textContent).not.toContain(controlValue);
    expect(container.textContent).not.toContain(bidiValue);
    expect(container.textContent).not.toContain("private@example.test");
    expect(container.textContent).not.toContain("hidden-token");
    expect(container.textContent).not.toContain("x".repeat(500));
  });

  it.each([
    ["numeric fields", { merchantName: 42, merchantId: 17 }],
    ["array fields", { merchantName: ["Northwind"], channel: ["ONLINE"] }],
    ["only blocked strings", { merchantName: "x".repeat(129), merchantId: "blocked\u202e", channel: "bad\nchannel" }],
    ["empty merchant record", {}],
    ["missing merchant record", undefined],
    ["non-record merchant data", ["Northwind"]],
    ["primitive merchant data", "Northwind"]
  ])("shows the neutral empty state for %s", (_caseName, merchantInfo) => {
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({ merchantInfo }),
      isLoading: false,
      error: null
    });

    renderPanel();

    const merchant = screen.getByRole("region", { name: "Merchant Investigation" });
    expect(merchant).toHaveTextContent("No displayable transaction merchant details are available.");
    expect(merchant).not.toHaveTextContent("Northwind");
  });

  it("does not enable detail fetch when panel reads are disabled", () => {
    renderPanel({ enabled: false });

    expect(useScoredTransactionDetail).toHaveBeenCalledWith(expect.objectContaining({
      transactionId: "txn-available-1",
      enabled: false
    }));
    expect(screen.getByText("Transaction risk intelligence is not available without transaction read permission.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Transaction Summary" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Diagnostic Boundary" })).toBeInTheDocument();
  });

  it.each([
    [availableDetail(), "AVAILABLE", "Engine Intelligence is available for this transaction."],
    [absentDetail(), "ABSENT", "No Engine Intelligence projection exists for this scored transaction."],
    [unavailableDetail(), "UNAVAILABLE", "The scored transaction was found, but Engine Intelligence could not be safely read."],
    [degradedDetail(), "DEGRADED", "Engine Intelligence is available with limitations."]
  ])("renders %s state with text label", (detail, status, copy) => {
    useScoredTransactionDetail.mockReturnValue({ detail, isLoading: false, error: null });

    renderPanel({ transactionId: detail.transactionId });

    const statusRegion = screen.getByRole("region", { name: "Engine Intelligence Status" });
    expect(within(statusRegion).getByText(status)).toBeInTheDocument();
    expect(within(statusRegion).getByText(copy, { exact: false })).toBeInTheDocument();
  });

  it("renders projected comparison and null comparison empty state", () => {
    const { rerender } = renderPanel();

    const comparison = screen.getByRole("region", { name: "Projected Comparison" });
    expect(within(comparison).getByText("DISAGREEMENT")).toBeInTheDocument();
    expect(within(comparison).getByText("Agreement status describes the Rules vs ML diagnostic comparison only.")).toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({
      detail: comparisonNullDetail(),
      isLoading: false,
      error: null
    });
    rerender(<TransactionRiskIntelligencePanel transactionId="txn-comparison-null-1" apiClient={{}} />);

    expect(screen.getByText("Projected engine comparison is not available for this transaction.")).toBeInTheDocument();
  });

  it("renders analyst recommendation after projected comparison and before engine results", () => {
    const { container } = renderPanel();

    const text = container.textContent;
    expect(screen.getByRole("region", { name: "Analyst Recommendation" })).toHaveTextContent("RECOMMEND_REVIEW");
    expect(text.indexOf("Projected Comparison")).toBeLessThan(text.indexOf("Analyst Recommendation"));
    expect(text.indexOf("Analyst Recommendation")).toBeLessThan(text.indexOf("Engine Results"));
  });

  it("renders engine results diagnostic signals and warnings sections", () => {
    const detail = availableDetail();
    useScoredTransactionDetail.mockReturnValue({
      detail: {
        ...detail,
        engineIntelligence: {
          ...detail.engineIntelligence,
          status: "DEGRADED",
          warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1 }]
        }
      },
      isLoading: false,
      error: null
    });

    renderPanel();

    expect(screen.getByRole("region", { name: "Engine Results" })).toHaveTextContent("rules.primary");
    expect(screen.getByRole("region", { name: "Diagnostic Signals" })).toHaveTextContent("FRAUD_SIGNAL");
    expect(screen.getByRole("region", { name: "Warnings and Limitations" })).toHaveTextContent("ENGINE_RESULT_LIMIT_APPLIED");
    expect(screen.getByText("Warnings are not operational instructions.")).toBeInTheDocument();
  });

  it("renders shared three-engine golden fixture without deriving hidden fields", () => {
    const golden = sharedEngineIntelligenceFixture("engine_intelligence_three_engine_golden.json");
    useScoredTransactionDetail.mockReturnValue({
      detail: availableDetail({
        transactionId: "txn-golden-detail",
        engineIntelligence: {
          status: "AVAILABLE",
          ...golden
        }
      }),
      isLoading: false,
      error: null
    });

    renderPanel({ transactionId: "txn-golden-detail" });

    const engines = screen.getByRole("region", { name: "Engine Results" });
    expect(within(engines).getByText("rules.primary")).toBeInTheDocument();
    expect(within(engines).getByText("ml.python.primary")).toBeInTheDocument();
    expect(within(engines).getByText("velocity.primary")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Projected Comparison" })).toHaveTextContent("RULES_VS_ML");
    expect(screen.queryByText("modelConfidence")).not.toBeInTheDocument();
  });

  it("renders empty bounded arrays safely", () => {
    useScoredTransactionDetail.mockReturnValue({
      detail: emptyArraysDetail(),
      isLoading: false,
      error: null
    });

    renderPanel({ transactionId: "txn-empty-arrays-1" });

    expect(screen.getByText("No bounded engine results are available in the projected diagnostics.")).toBeInTheDocument();
    expect(screen.getByText("No bounded diagnostic signals are available.")).toBeInTheDocument();
    expect(screen.getByText("No warnings are present in the projected diagnostics.")).toBeInTheDocument();
  });

  it.each([
    [{ status: 400 }, "This transaction identifier is invalid."],
    [permissionDeniedError(401), "You do not have permission to read this transaction risk intelligence."],
    [permissionDeniedError(403), "You do not have permission to read this transaction risk intelligence."],
    [notFoundError(), "Scored transaction not found."],
    [networkError(), "Transaction risk intelligence is temporarily unavailable."],
    [{ name: "AbortError" }, "Transaction risk intelligence loading was interrupted before diagnostics could be displayed."]
  ])("renders safe error state %#", (error, copy) => {
    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: false, error });

    renderPanel();

    expect(screen.getByText(copy)).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Diagnostic Boundary" })).toBeInTheDocument();
  });

  it("renders loading empty selection and invalid response states", () => {
    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: true, error: null });
    const { rerender } = renderPanel();
    expect(screen.getByText("Loading transaction risk intelligence...")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: false, error: null });
    rerender(<TransactionRiskIntelligencePanel transactionId="" apiClient={{}} />);
    expect(screen.getByText("No transaction selected.")).toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({
      detail: null,
      isLoading: false,
      error: invalidResponseError()
    });
    rerender(<TransactionRiskIntelligencePanel transactionId="txn-available-1" apiClient={{}} />);
    expect(screen.getByText("The transaction risk intelligence response is malformed or missing required safety fields.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();
  });

  it("renders analyst feedback form with boundary copy and neutral labels", () => {
    const { container } = renderPanel();
    const text = container.textContent.toLowerCase();

    expect(screen.getByRole("region", { name: "Analyst Feedback" })).toHaveTextContent("Feedback records analyst review outcome only.");
    expect(screen.getByRole("button", { name: "Record feedback" })).toBeInTheDocument();
    expect(screen.getByLabelText("Mark as confirmed fraud")).toBeInTheDocument();
    expect(screen.getByLabelText("Mark as confirmed legitimate")).toBeInTheDocument();
    expect(screen.getByLabelText("Mark as inconclusive")).toBeInTheDocument();
    expect(screen.getByLabelText("Needs more information")).toBeInTheDocument();
    expect(text).not.toContain("safe to approve");
    expect(text).not.toContain("recommended action");
    expect(text).not.toContain("apply recommendation");
    expect(text).not.toContain("accept recommendation");
    expect(text).not.toContain("reject recommendation");
    expect(text).not.toContain("rawmlrequest");
    expect(text).not.toContain("rawmlresponse");
    expect(text).not.toContain("rawfeaturevector");
    expect(text).not.toContain("rawevidence");
    expect(text).not.toContain("groundtruth");
    expect(text).not.toContain("traininglabel");
  });

  it("submits bounded confirmed fraud feedback only on explicit form submit", async () => {
    const submit = vi.fn();
    useFraudFeedback.mockReturnValue({
      feedback: null,
      isLoading: false,
      error: null,
      canReadFeedback: true,
      canCreateFeedback: true,
      submitState: "idle",
      submitError: null,
      submit
    });
    renderPanel();

    expect(submit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Record feedback" }));

    await waitFor(() => expect(submit).toHaveBeenCalledWith({
      analystDecision: "MARKED_FRAUD",
      feedbackLabel: "CONFIRMED_FRAUD",
      scoringOccurrenceToken: "a".repeat(64),
      decisionReasonCodes: ["ANALYST_CONFIRMED_FRAUD"],
      notes: null
    }));
  });

  it("trims bounded analyst feedback notes before submit", async () => {
    const submit = vi.fn();
    useFraudFeedback.mockReturnValue({
      feedback: null,
      isLoading: false,
      error: null,
      canReadFeedback: true,
      canCreateFeedback: true,
      submitState: "idle",
      submitError: null,
      submit
    });
    renderPanel();

    fireEvent.change(screen.getByLabelText("Notes"), { target: { value: "  bounded note  " } });
    fireEvent.click(screen.getByRole("button", { name: "Record feedback" }));

    await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({
      notes: "bounded note"
    })));
  });

  it("renders existing feedback and disables duplicate submission surface", () => {
    useFraudFeedback.mockReturnValue({
      feedback: {
        feedbackLabel: "CONFIRMED_LEGITIMATE",
        analystDecision: "MARKED_LEGITIMATE",
        labelSource: "ANALYST_REVIEW",
        feedbackStatus: "RECORDED",
        scoringOccurrenceRelation: "PRIOR",
        createdAt: "2026-06-25T10:15:30Z",
        decisionReasonCodes: ["ANALYST_CONFIRMED_LEGITIMATE"],
        notesPresent: true,
        notes: "Sensitive analyst note"
      },
      isLoading: false,
      error: null,
      canReadFeedback: true,
      canCreateFeedback: true,
      submitState: "idle",
      submitError: null,
      submit: vi.fn()
    });

    renderPanel();

    const feedbackRegion = screen.getByRole("region", { name: "Analyst Feedback" });
    expect(screen.getByText("Feedback recorded")).toBeInTheDocument();
    expect(within(feedbackRegion).getByText("Notes")).toBeInTheDocument();
    expect(within(feedbackRegion).getByText("Present")).toBeInTheDocument();
    expect(within(feedbackRegion).getByText("This feedback belongs to a prior scoring occurrence and does not cover the current scoring.")).toBeInTheDocument();
    expect(screen.queryByText("Sensitive analyst note")).not.toBeInTheDocument();
    expect(screen.getByText("One active feedback record is already present for this transaction.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
  });

  it("renders existing feedback without notes indicator when notes are absent", () => {
    useFraudFeedback.mockReturnValue({
      feedback: {
        feedbackLabel: "CONFIRMED_LEGITIMATE",
        analystDecision: "MARKED_LEGITIMATE",
        labelSource: "ANALYST_REVIEW",
        feedbackStatus: "RECORDED",
        createdAt: "2026-06-25T10:15:30Z",
        decisionReasonCodes: ["ANALYST_CONFIRMED_LEGITIMATE"],
        notesPresent: false
      },
      isLoading: false,
      error: null,
      canReadFeedback: true,
      canCreateFeedback: true,
      submitState: "idle",
      submitError: null,
      submit: vi.fn()
    });

    renderPanel();

    const feedbackRegion = screen.getByRole("region", { name: "Analyst Feedback" });
    expect(within(feedbackRegion).getByText("Notes")).toBeInTheDocument();
    expect(within(feedbackRegion).getByText("None")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
  });

  it.each([
    [{ status: 400 }, "Feedback request failed validation."],
    [{ status: 401 }, "You do not have permission to read or record analyst feedback."],
    [{ status: 403 }, "You do not have permission to read or record analyst feedback."],
    [{ status: 404 }, "Scored transaction or feedback was not found."],
    [{ status: 409, reasonCode: "FRAUD_FEEDBACK_ALREADY_RECORDED" }, "Feedback already exists for this transaction."],
    [{ status: 409, reasonCode: "CONFLICT" }, "Feedback could not be recorded because transaction state changed. Refresh the transaction list and review the current scoring."],
    [{ status: 503 }, "Analyst feedback is temporarily unavailable."],
    [new Error("backend raw failure"), "Analyst feedback could not be loaded or recorded."]
  ])("renders safe analyst feedback error %#", (error, copy) => {
    useFraudFeedback.mockReturnValue({
      feedback: null,
      isLoading: false,
      error,
      canReadFeedback: true,
      canCreateFeedback: true,
      submitState: "idle",
      submitError: null,
      submit: vi.fn()
    });

    renderPanel();

    expect(screen.getByText(copy)).toBeInTheDocument();
  });

  it("shows a distinct stale scoring message after a feedback conflict", () => {
    const refreshTransactions = vi.fn();
    useFraudFeedback.mockReturnValue({
      feedback: null,
      isLoading: false,
      error: null,
      canReadFeedback: true,
      canCreateFeedback: true,
      submitState: "stale-occurrence",
      submitError: { status: 409, error: "FRAUD_FEEDBACK_SCORING_OCCURRENCE_CHANGED" },
      submit: vi.fn()
    });

    renderPanel({ onRefreshTransactions: refreshTransactions });

    expect(screen.getByRole("alert")).toHaveTextContent("Refresh the transaction list and review the current occurrence");
    expect(screen.queryByText("Feedback already exists for this transaction.")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh scored transactions" }));
    expect(refreshTransactions).toHaveBeenCalledTimes(1);
  });

  it("does not render feedback form when runtime cannot read fraud feedback", () => {
    useFraudFeedback.mockReturnValue({
      feedback: null,
      isLoading: false,
      error: null,
      canReadFeedback: false,
      canCreateFeedback: true,
      submitState: "idle",
      submitError: null,
      submit: vi.fn()
    });

    renderPanel();

    expect(screen.getByText("Analyst feedback is not available in this runtime.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Mark as confirmed fraud")).not.toBeInTheDocument();
  });

  it("does not render submit surface when runtime cannot create fraud feedback", () => {
    const submit = vi.fn();
    useFraudFeedback.mockReturnValue({
      feedback: null,
      isLoading: false,
      error: null,
      canReadFeedback: true,
      canCreateFeedback: false,
      submitState: "idle",
      submitError: null,
      submit
    });

    renderPanel();

    expect(screen.getByText("Analyst feedback is not available in this runtime.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });
});

function renderPanel(props = {}) {
  return render(<TransactionRiskIntelligencePanel transactionId="txn-available-1" apiClient={{}} {...props} />);
}

function sharedEngineIntelligenceFixture(name) {
  return JSON.parse(readFileSync(resolve(
    process.cwd(),
    "../common-events/src/test/resources/fixtures/engine-intelligence",
    name
  ), "utf8"));
}
