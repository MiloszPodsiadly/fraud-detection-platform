import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FraudCaseWorkQueuePanel } from "./FraudCaseWorkQueuePanel.jsx";

describe("FraudCaseWorkQueuePanel", () => {
  it("renders the analyst table columns and minimal allowed fields", () => {
    renderPanel({
      queue: {
        content: [
          workQueueItem({
            customerId: "customer-secret",
            transactionIds: ["tx-secret"],
            merchantName: "Sensitive Merchant",
            amount: "9999.00",
            rawReason: "raw fraud reason",
            linkedAlertIds: ["alert-secret"],
            cursor: "raw-cursor",
            auditDetails: "audit-internal",
            idempotencyKey: "idem-secret"
          })
        ],
        hasNext: false,
        nextCursor: "opaque-next-cursor"
      }
    });

    const table = screen.getByRole("table", { name: "Fraud case work queue table" });
    expect(within(table).getByRole("columnheader", { name: "Case" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Status" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Priority" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Risk" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Assignee" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Age" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Updated" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "SLA" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Alerts" })).toBeInTheDocument();
    expect(within(table).getByText("CASE-2026-0001")).toBeInTheDocument();
    expect(within(table).getByText("investigator-1")).toBeInTheDocument();
    expect(within(table).getByText("NEAR BREACH")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Open fraud case CASE-2026-0001" }).length).toBeGreaterThan(0);
    expect(document.body).not.toHaveTextContent("customer-secret");
    expect(document.body).not.toHaveTextContent("tx-secret");
    expect(document.body).not.toHaveTextContent("Sensitive Merchant");
    expect(document.body).not.toHaveTextContent("9999.00");
    expect(document.body).not.toHaveTextContent("raw fraud reason");
    expect(document.body).not.toHaveTextContent("alert-secret");
    expect(document.body).not.toHaveTextContent("opaque-next-cursor");
    expect(document.body).not.toHaveTextContent("audit-internal");
    expect(document.body).not.toHaveTextContent("idem-secret");
    expect(screen.queryByRole("button", { name: /assign|close|export|csv|bulk|copy/i })).not.toBeInTheDocument();
  });

  it("renders mobile cards with the same allowed fields", () => {
    renderPanel();

    const cards = screen.getByLabelText("Fraud case work queue cards");
    expect(within(cards).getByText("CASE-2026-0001")).toBeInTheDocument();
    expect(within(cards).getByText("Unassigned")).toBeInTheDocument();
    expect(within(cards).getByText("2h 0m")).toBeInTheDocument();
    expect(within(cards).getByRole("button", { name: "Open fraud case CASE-2026-0001" })).toBeInTheDocument();
  });

  it("deduplicates duplicate case ids in the rendered table", () => {
    renderPanel({
      queue: {
        content: [
          workQueueItem({ caseId: "case-1", caseNumber: "CASE-1" }),
          workQueueItem({ caseId: "case-1", caseNumber: "CASE-1 duplicate" })
        ],
        hasNext: false
      }
    });

    expect(screen.getAllByText("CASE-1")).toHaveLength(2);
    expect(screen.queryByText("CASE-1 duplicate")).not.toBeInTheDocument();
  });

  it("keeps filter drafts controlled and applies only on explicit action", () => {
    const onDraftChange = vi.fn();
    const onApplyFilters = vi.fn();
    renderPanel({
      request: {
        ...defaultRequest(),
        status: "OPEN",
        priority: "HIGH"
      },
      draftRequest: {
        ...defaultRequest(),
        status: "CLOSED",
        priority: "HIGH"
      },
      onDraftChange,
      onApplyFilters
    });

    expect(screen.getByText("Status: OPEN")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "REOPENED" } });
    expect(onDraftChange).toHaveBeenCalledWith("status", "REOPENED");
    expect(onApplyFilters).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Apply fraud case work queue filters" }));
    expect(onApplyFilters).toHaveBeenCalledTimes(1);
  });

  it("disables apply when filters are invalid", () => {
    renderPanel({
      draftRequest: {
        ...defaultRequest(),
        createdFrom: "invalid"
      },
      validationError: "Created from is not a valid local date and time."
    });

    expect(screen.getByText("Created from is not a valid local date and time.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply fraud case work queue filters" })).toBeDisabled();
  });

  it("warns when an appended queue slice contains duplicate cases", () => {
    const onRefreshFirstSlice = vi.fn();
    renderPanel({
      queue: {
        content: [workQueueItem({ caseId: "case-1" })],
        hasNext: true,
        duplicateCaseIds: ["case-1"]
      },
      onRefreshFirstSlice
    });

    expect(screen.getByRole("alert")).toHaveTextContent("Queue changed while loading.");
    fireEvent.click(within(screen.getByRole("alert")).getByRole("button", { name: "Refresh fraud case work queue from first slice" }));

    expect(onRefreshFirstSlice).toHaveBeenCalledTimes(1);
  });

  it("uses load more without exposing the cursor in DOM, URL, storage, or console", () => {
    const onLoadMore = vi.fn();
    const consoleSpy = vi.spyOn(console, "log").mockImplementation(() => {});
    renderPanel({
      queue: {
        content: [workQueueItem()],
        hasNext: true,
        nextCursor: "opaque-sensitive-cursor"
      },
      onLoadMore
    });

    fireEvent.click(screen.getByRole("button", { name: "Load more fraud cases" }));

    expect(onLoadMore).toHaveBeenCalledTimes(1);
    expect(document.body).not.toHaveTextContent("opaque-sensitive-cursor");
    expect(window.location.search).not.toContain("opaque-sensitive-cursor");
    expect(storageContains(window.localStorage, "opaque-sensitive-cursor")).toBe(false);
    expect(storageContains(window.sessionStorage, "opaque-sensitive-cursor")).toBe(false);
    expect(consoleSpy).not.toHaveBeenCalled();
    consoleSpy.mockRestore();
  });

  it.each([
    [{ status: 401 }, "Session required", "No analyst session is currently active."],
    [{ status: 403 }, "Access denied", "Your session does not include FRAUD_CASE_READ."],
    [{ status: 503 }, "Work queue temporarily unavailable", "Sensitive-read audit is fail-closed."],
    [{ status: 400, error: "INVALID_CURSOR" }, "Queue position expired", "Refresh from the first slice"],
    [{ status: 400, error: "INVALID_SORT", message: "INVALID_SORT" }, "Invalid work queue request", "INVALID_SORT"]
  ])("renders controlled error state for %o", (error, title, expectedText) => {
    renderPanel({ queue: { content: [], hasNext: false }, error });

    expect(screen.getByRole("heading", { name: title })).toBeInTheDocument();
    expect(screen.getByText(new RegExp(expectedText))).toBeInTheDocument();
  });

  it("marks regulated failure states with alert role", () => {
    renderPanel({ queue: { content: [], hasNext: false }, error: { status: 503 } });
    expect(screen.getByRole("alert")).toHaveTextContent("Work queue temporarily unavailable");
  });

  it("renders skeleton rows while initially loading", () => {
    renderPanel({ queue: { content: [], hasNext: false }, isLoading: true });
    expect(screen.getByText("Loading fraud case work queue")).toBeInTheDocument();
    expect(screen.getAllByText("", { selector: ".skeletonBlock" }).length).toBeGreaterThan(0);
  });
});

function renderPanel(overrides = {}) {
  const props = {
    queue: { content: [workQueueItem({ assignedInvestigatorId: "" })], hasNext: false, nextCursor: null, sort: "createdAt,desc", size: 20 },
    request: defaultRequest(),
    draftRequest: defaultRequest(),
    isLoading: false,
    error: null,
    warning: null,
    validationError: null,
    lastRefreshedAt: null,
    onDraftChange: vi.fn(),
    onApplyFilters: vi.fn(),
    onResetFilters: vi.fn(),
    onLoadMore: vi.fn(),
    onRetry: vi.fn(),
    onRefreshFirstSlice: vi.fn(),
    onOpenCase: vi.fn(),
    ...overrides
  };
  return render(<FraudCaseWorkQueuePanel {...props} />);
}

function defaultRequest() {
  return {
    size: 20,
    status: "ALL",
    priority: "ALL",
    riskLevel: "ALL",
    assignee: "",
    createdFrom: "",
    createdTo: "",
    updatedFrom: "",
    updatedTo: "",
    linkedAlertId: "",
    sort: "createdAt,desc"
  };
}

function workQueueItem(overrides = {}) {
  return {
    caseId: "case-1",
    caseNumber: "CASE-2026-0001",
    status: "OPEN",
    priority: "HIGH",
    riskLevel: "CRITICAL",
    assignedInvestigatorId: "investigator-1",
    createdAt: "2026-05-11T10:00:00Z",
    updatedAt: "2026-05-11T11:00:00Z",
    caseAgeSeconds: 7200,
    lastUpdatedAgeSeconds: 600,
    slaStatus: "NEAR_BREACH",
    slaDeadlineAt: "2026-05-12T10:00:00Z",
    linkedAlertCount: 2,
    ...overrides
  };
}

function storageContains(storage, value) {
  if (!storage) {
    return false;
  }
  if (typeof storage.getItem === "function" && typeof storage.length === "number" && typeof storage.key === "function") {
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (`${key} ${storage.getItem(key)}`.includes(value)) {
        return true;
      }
    }
    return false;
  }
  return JSON.stringify(storage).includes(value);
}
