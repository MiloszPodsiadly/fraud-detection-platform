import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FraudTransactionWorkspacePage } from "./FraudTransactionWorkspacePage.jsx";

describe("FraudTransactionWorkspacePage filter draft ownership", () => {
  it("preserves filters for one owner and resets them synchronously for another", () => {
    const ownerA = {};
    const ownerB = {};
    const onAlertPageChange = vi.fn();
    const { rerender } = render(
      <FraudTransactionWorkspacePage
        alertPage={page(0, "customer-a")}
        viewIdentity={ownerA}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={onAlertPageChange}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );

    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "customer-a" } });
    fireEvent.change(screen.getByLabelText("Risk"), { target: { value: "HIGH" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "OPEN" } });
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(onAlertPageChange).toHaveBeenCalledWith(1);

    rerender(
      <FraudTransactionWorkspacePage
        alertPage={page(1, "customer-a-next")}
        viewIdentity={ownerA}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={onAlertPageChange}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );
    expect(screen.getByLabelText("Search")).toHaveValue("customer-a");
    expect(screen.getByLabelText("Risk")).toHaveValue("HIGH");
    expect(screen.getByLabelText("Status")).toHaveValue("OPEN");
    expect(screen.getByText("customer-a-next")).toBeInTheDocument();

    rerender(
      <FraudTransactionWorkspacePage
        alertPage={page(0, "customer-b")}
        viewIdentity={ownerB}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={onAlertPageChange}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );

    expect(screen.getByLabelText("Search")).toHaveValue("");
    expect(screen.getByLabelText("Risk")).toHaveValue("ALL");
    expect(screen.getByLabelText("Status")).toHaveValue("ALL");
    expect(screen.getByText("customer-b")).toBeInTheDocument();
  });
});

describe("Alert Queue local filtering and backend pagination", () => {
  it.each([
    ["search", (filters) => fireEvent.change(filters.search, { target: { value: "no-local-match" } })],
    ["risk", (filters) => fireEvent.change(filters.risk, { target: { value: "LOW" } })],
    ["status", (filters) => fireEvent.change(filters.status, { target: { value: "ESCALATED" } })]
  ])("keeps backend pagination available when the local %s filter has no matches", (_name, setFilter) => {
    const owner = {};
    const onAlertPageChange = vi.fn();
    const props = {
      alertPage: page(0, "customer-a", { totalPages: 3, totalElements: 30 }),
      viewIdentity: owner,
      isLoading: false,
      error: null,
      onRetry: vi.fn(),
      onAlertPageChange,
      onAlertPageSizeChange: vi.fn(),
      onOpenAlert: vi.fn()
    };
    const { rerender } = render(<FraudTransactionWorkspacePage {...props} />);
    setFilter({
      search: screen.getByLabelText("Search"),
      risk: screen.getByLabelText("Risk"),
      status: screen.getByLabelText("Status")
    });

    expect(screen.getByText("No alerts on this loaded page match the local filters.")).toBeInTheDocument();
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(onAlertPageChange).toHaveBeenCalledWith(1);

    rerender(<FraudTransactionWorkspacePage {...props} alertPage={page(1, "customer-next", { totalPages: 3, totalElements: 30 })} />);
    expect(screen.getByText("No alerts on this loaded page match the local filters.")).toBeInTheDocument();
    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
    expect(screen.getByLabelText("Search")).toHaveValue(_name === "search" ? "no-local-match" : "");
    expect(screen.getByLabelText("Risk")).toHaveValue(_name === "risk" ? "LOW" : "ALL");
    expect(screen.getByLabelText("Status")).toHaveValue(_name === "status" ? "ESCALATED" : "ALL");
  });

  it("shows one-page controls for loaded alerts and no pagination for an empty queue", () => {
    const props = {
      viewIdentity: {},
      isLoading: false,
      error: null,
      onRetry: vi.fn(),
      onAlertPageChange: vi.fn(),
      onAlertPageSizeChange: vi.fn(),
      onOpenAlert: vi.fn()
    };
    const { rerender } = render(
      <FraudTransactionWorkspacePage {...props} alertPage={page(0, "customer-a", { totalPages: 1, totalElements: 1 })} />
    );
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "no-local-match" } });
    expect(screen.getByText("No alerts on this loaded page match the local filters.")).toBeInTheDocument();
    expect(screen.getByText("Page 1 of 1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();

    rerender(
      <FraudTransactionWorkspacePage
        {...props}
        alertPage={{ content: [], page: 0, size: 10, totalPages: 0, totalElements: 0 }}
      />
    );
    expect(screen.getByText("No alerts on this loaded page match the local filters.")).toBeInTheDocument();
    expect(screen.queryByText(/Page \d+ of \d+/)).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});

function page(pageNumber, customerId, { totalPages = 2, totalElements = 20 } = {}) {
  return {
    content: [{
      alertId: `alert-${pageNumber}`,
      transactionId: `transaction-${pageNumber}`,
      customerId,
      alertReason: "High risk",
      riskLevel: "HIGH",
      alertStatus: "OPEN"
    }],
    page: pageNumber,
    size: 10,
    totalPages,
    totalElements
  };
}
