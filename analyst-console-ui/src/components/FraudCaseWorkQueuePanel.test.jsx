import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FraudCaseWorkQueuePanel } from "./FraudCaseWorkQueuePanel.jsx";

describe("FraudCaseWorkQueuePanel", () => {
  it("renders only minimal work queue fields and no mutation/export controls", () => {
    renderPanel({
      queue: {
        content: [
          workQueueItem({
            customerId: "customer-secret",
            transactionIds: ["tx-secret"],
            rawReason: "raw fraud reason",
            linkedAlertIds: ["alert-secret"]
          })
        ],
        hasNext: false,
        nextCursor: "opaque-next-cursor"
      }
    });

    expect(screen.getByRole("heading", { name: "Fraud Case Work Queue" })).toBeInTheDocument();
    expect(screen.getByText(/Read-only investigator queue/)).toBeInTheDocument();
    expect(screen.getByText("CASE-2026-0001")).toBeInTheDocument();
    expect(screen.getByText("investigator-1")).toBeInTheDocument();
    expect(screen.getByText("NEAR BREACH")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open case" })).toBeInTheDocument();
    expect(screen.queryByText("customer-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("tx-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("raw fraud reason")).not.toBeInTheDocument();
    expect(screen.queryByText("alert-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("opaque-next-cursor")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /assign/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /close/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /export|csv|bulk|copy/i })).not.toBeInTheDocument();
  });

  it("deduplicates duplicate case ids in the rendered list", () => {
    renderPanel({
      queue: {
        content: [
          workQueueItem({ caseId: "case-1", caseNumber: "CASE-1" }),
          workQueueItem({ caseId: "case-1", caseNumber: "CASE-1 duplicate" })
        ],
        hasNext: false
      }
    });

    expect(screen.getByText("CASE-1")).toBeInTheDocument();
    expect(screen.queryByText("CASE-1 duplicate")).not.toBeInTheDocument();
  });

  it("applies filter and sort drafts only when requested", () => {
    const onRequestChange = vi.fn();
    renderPanel({
      onRequestChange,
      request: {
        ...defaultRequest(),
        status: "OPEN",
        priority: "HIGH"
      }
    });

    expect(screen.getByText("Status: OPEN")).toBeInTheDocument();
    expect(screen.getByText("Priority: HIGH")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "CLOSED" } });
    fireEvent.change(screen.getByLabelText("Sort"), { target: { value: "updatedAt,asc" } });
    expect(onRequestChange).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    fireEvent.click(screen.getByRole("button", { name: "Reset filters" }));

    expect(onRequestChange).toHaveBeenCalledWith(expect.objectContaining({
      status: "CLOSED",
      sort: "updatedAt,asc",
      cursor: null
    }));
    expect(onRequestChange).toHaveBeenCalledWith(expect.objectContaining({
      status: "ALL",
      priority: "ALL",
      cursor: null,
      sort: "createdAt,desc"
    }));
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
    fireEvent.click(screen.getByRole("button", { name: "Refresh from first slice" }));

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

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(onLoadMore).toHaveBeenCalledTimes(1);
    expect(document.body).not.toHaveTextContent("opaque-sensitive-cursor");
    expect(window.location.search).not.toContain("opaque-sensitive-cursor");
    expect(storageContains(window.localStorage, "opaque-sensitive-cursor")).toBe(false);
    expect(storageContains(window.sessionStorage, "opaque-sensitive-cursor")).toBe(false);
    expect(consoleSpy).not.toHaveBeenCalled();
    consoleSpy.mockRestore();
  });

  it.each([
    [{ status: 401 }, "Sign-in required", "Start a valid session before loading the fraud case work queue."],
    [{ status: 403 }, "Access denied", "You do not have access to the fraud case work queue."],
    [{ status: 503 }, "Work queue unavailable", "fail-closed"],
    [{ status: 400, error: "INVALID_CURSOR" }, "Queue position expired", "Refresh queue"],
    [{ status: 400, error: "INVALID_SORT", message: "INVALID_SORT" }, "Invalid work queue request", "INVALID_SORT"]
  ])("renders controlled error state for %o", (error, title, expectedText) => {
    renderPanel({ queue: { content: [], hasNext: false }, error });

    expect(screen.getByRole("heading", { name: title })).toBeInTheDocument();
    expect(screen.getByText(new RegExp(expectedText))).toBeInTheDocument();
  });
});

function renderPanel(overrides = {}) {
  const props = {
    queue: { content: [workQueueItem()], hasNext: false, nextCursor: null, sort: "createdAt,desc", size: 20 },
    request: defaultRequest(),
    isLoading: false,
    error: null,
    onRequestChange: vi.fn(),
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
