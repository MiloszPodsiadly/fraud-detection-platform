import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useAlertQueue } from "../workspace/useAlertQueue.js";
import { FraudTransactionWorkspacePage } from "./FraudTransactionWorkspacePage.jsx";

describe("FraudTransactionWorkspacePage capability and load state", () => {
  it.each([
    [false, "No access", "Alert read access is unavailable."],
    [undefined, "Alerts unavailable", "Alert read capability has not been established for this session."]
  ])("does not show filters or stale rows when alert capability is %s", (canReadAlerts, title, message) => {
    render(
      <FraudTransactionWorkspacePage
        alertPage={page(0, "private-customer")}
        viewIdentity={{}}
        canReadAlerts={canReadAlerts}
        hasLoaded={false}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: title })).toBeInTheDocument();
    expect(screen.getByText(message)).toBeInTheDocument();
    expect(screen.queryByLabelText("Search")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Page size")).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("private-customer")).not.toBeInTheDocument();
    expect(screen.queryByText("No alerts on this loaded page match the local filters.")).not.toBeInTheDocument();
  });

  it("shows loading rather than a confirmed empty queue before the first authorized read completes", () => {
    render(
      <FraudTransactionWorkspacePage
        alertPage={{ content: [], page: 0, size: 10, totalPages: 0, totalElements: 0 }}
        viewIdentity={{}}
        canReadAlerts
        hasLoaded={false}
        isLoading
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );

    expect(screen.getByText("Loading fraud alerts...")).toBeInTheDocument();
    expect(screen.queryByText("No alerts on this loaded page match the local filters.")).not.toBeInTheDocument();
  });

  it("does not claim a loaded empty queue when an authorized runtime has no page or request", () => {
    render(
      <FraudTransactionWorkspacePage
        alertPage={{ content: [], page: 0, size: 10, totalPages: 0, totalElements: 0 }}
        viewIdentity={{}}
        canReadAlerts
        hasLoaded={false}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Alert queue not loaded" })).toBeInTheDocument();
    expect(screen.queryByText("No alerts on this loaded page match the local filters.")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Search")).not.toBeInTheDocument();
  });

  it("shows the empty queue state only after an authorized read loaded an empty page", () => {
    render(
      <FraudTransactionWorkspacePage
        alertPage={{ content: [], page: 0, size: 10, totalPages: 0, totalElements: 0 }}
        viewIdentity={{}}
        canReadAlerts
        hasLoaded
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );

    expect(screen.getByLabelText("Search")).toBeInTheDocument();
    expect(screen.getByText("No alerts on this loaded page match the local filters.")).toBeInTheDocument();
  });
});

describe("FraudTransactionWorkspacePage filter draft ownership", () => {
  it("preserves filters for one owner and resets them synchronously for another", () => {
    const ownerA = {};
    const ownerB = {};
    const onAlertPageChange = vi.fn();
    const { rerender } = render(
      <FraudTransactionWorkspacePage
        alertPage={page(0, "customer-a")}
        viewIdentity={ownerA}
        canReadAlerts
        hasLoaded
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
        canReadAlerts
        hasLoaded
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
        canReadAlerts
        hasLoaded
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
      canReadAlerts: true,
      hasLoaded: true,
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
      canReadAlerts: true,
      hasLoaded: true,
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

describe("Alert Queue recovery when the queue shrinks", () => {
  it("recovers an empty out-of-range page to page zero once and keeps the local filter draft", async () => {
    const requests = [];
    const responses = [
      queuePage(0, 30, "a"),
      queuePage(1, 30, "a"),
      queuePage(2, 30, "a"),
      queuePage(2, 0, "a"),
      queuePage(0, 0, "a")
    ];
    const apiClient = {
      listAlerts: vi.fn(({ page: pageNumber, size }) => {
        requests.push({ page: pageNumber, size });
        return Promise.resolve(responses[requests.length - 1]);
      })
    };
    render(<AlertQueueHookPage apiClient={apiClient} session={alertSession("a")} />);

    expect(await screen.findByRole("button", { name: "Review" })).toHaveAttribute("data-detail-origin", "alert-alert-a-0");
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "saved-filter" } });
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Refresh queue" }));
    await waitFor(() => {
      expect(screen.getByTestId("queue-request-page")).toHaveTextContent("0");
      expect(screen.getByTestId("queue-loaded")).toHaveTextContent("true");
      expect(screen.getByTestId("queue-total")).toHaveTextContent("0");
    });

    expect(requests).toEqual([
      { page: 0, size: 10 },
      { page: 1, size: 10 },
      { page: 2, size: 10 },
      { page: 2, size: 10 },
      { page: 0, size: 10 }
    ]);
    expect(screen.getByLabelText("Search")).toHaveValue("saved-filter");
    expect(screen.queryByText(/Page \d+ of \d+/)).not.toBeInTheDocument();
  });

  it("recovers a page beyond the new last page when rows remain", async () => {
    const requests = [];
    const apiClient = {
      listAlerts: vi.fn(({ page: pageNumber, size }) => {
        requests.push({ page: pageNumber, size });
        if (requests.length <= 5) return Promise.resolve(queuePage(pageNumber, 50, "a"));
        if (requests.length === 6) return Promise.resolve(queuePage(pageNumber, 15, "a"));
        return Promise.resolve(queuePage(pageNumber, 15, "a"));
      })
    };
    render(<AlertQueueHookPage apiClient={apiClient} session={alertSession("a")} />);

    expect(await screen.findByRole("button", { name: "Review" })).toHaveAttribute("data-detail-origin", "alert-alert-a-0");
    for (let pageNumber = 1; pageNumber <= 4; pageNumber += 1) {
      fireEvent.click(screen.getByRole("button", { name: "Next" }));
      expect(await screen.findByText(`Page ${pageNumber + 1} of 5`)).toBeInTheDocument();
    }

    fireEvent.click(screen.getByRole("button", { name: "Refresh queue" }));
    await waitFor(() => {
      expect(screen.getByTestId("queue-request-page")).toHaveTextContent("0");
      expect(screen.getByTestId("queue-loaded")).toHaveTextContent("true");
      expect(screen.getByText("Page 1 of 2")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Review" })).toHaveAttribute("data-detail-origin", "alert-alert-a-0");
    });

    expect(requests.map(({ page: pageNumber }) => pageNumber)).toEqual([0, 1, 2, 3, 4, 4, 0]);
    expect(screen.getByTestId("queue-total")).toHaveTextContent("15");
  });

  it("does not loop when the page-zero recovery request fails", async () => {
    const requests = [];
    const apiClient = {
      listAlerts: vi.fn(({ page: pageNumber, size }) => {
        requests.push({ page: pageNumber, size });
        if (requests.length <= 3) return Promise.resolve(queuePage(pageNumber, 30, "a"));
        if (requests.length === 4) return Promise.resolve(queuePage(pageNumber, 0, "a"));
        return Promise.reject(new Error("private storage host and customer data"));
      })
    };
    render(<AlertQueueHookPage apiClient={apiClient} session={alertSession("a")} />);

    expect(await screen.findByRole("button", { name: "Review" })).toHaveAttribute("data-detail-origin", "alert-alert-a-0");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh queue" }));

    await waitFor(() => expect(screen.getByTestId("queue-error")).toHaveTextContent(
      "Alerts are temporarily unavailable. Please retry."
    ));
    expect(requests.map(({ page: pageNumber }) => pageNumber)).toEqual([0, 1, 2, 2, 0]);
    expect(screen.getByTestId("queue-error")).toHaveTextContent("Alerts are temporarily unavailable. Please retry.");
  });

  it("fences a pending page-zero recovery when the queue owner changes", async () => {
    const pendingARecovery = deferred();
    const requestsA = [];
    let recoverySignal;
    const clientA = {
      listAlerts: vi.fn(({ page: pageNumber, size }, { signal }) => {
        requestsA.push(pageNumber);
        if (requestsA.length <= 3) return Promise.resolve(queuePage(pageNumber, 30, "a"));
        if (requestsA.length === 4) return Promise.resolve(queuePage(pageNumber, 0, "a"));
        recoverySignal = signal;
        return pendingARecovery.promise;
      })
    };
    const clientB = {
      listAlerts: vi.fn(({ page: pageNumber }) => Promise.resolve(queuePage(pageNumber, 1, "b")))
    };
    const { rerender } = render(
      <AlertQueueHookPage apiClient={clientA} session={alertSession("a")} />
    );

    expect(await screen.findByRole("button", { name: "Review" })).toHaveAttribute("data-detail-origin", "alert-alert-a-0");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh queue" }));
    await waitFor(() => expect(requestsA).toHaveLength(5));

    rerender(<AlertQueueHookPage apiClient={clientB} session={alertSession("b")} />);
    expect(await screen.findByRole("button", { name: "Review" })).toHaveAttribute("data-detail-origin", "alert-alert-b-0");
    expect(recoverySignal.aborted).toBe(true);
    expect(clientB.listAlerts).toHaveBeenCalledTimes(1);

    await act(async () => pendingARecovery.resolve(queuePage(0, 1, "a-late")));
    expect(screen.getByRole("button", { name: "Review" })).toHaveAttribute("data-detail-origin", "alert-alert-b-0");
    expect(screen.queryByRole("button", { name: "Review" })).not.toHaveAttribute("data-detail-origin", "alert-alert-a-late-0");
    expect(clientB.listAlerts).toHaveBeenCalledTimes(1);
  });
});

function AlertQueueHookPage({ apiClient, session }) {
  const queue = useAlertQueue({ apiClient, enabled: true, session, authProvider: alertAuthProvider });
  return (
    <>
      <output data-testid="queue-request-page">{queue.request.page}</output>
      <output data-testid="queue-loaded">{String(queue.hasLoaded)}</output>
      <output data-testid="queue-total">{queue.page.totalElements}</output>
      <output data-testid="queue-error">{queue.error?.message ?? ""}</output>
      <button type="button" onClick={() => { void queue.refresh(); }}>Refresh queue</button>
      <FraudTransactionWorkspacePage
        alertPage={queue.page}
        viewIdentity={queue.viewIdentity}
        canReadAlerts
        hasLoaded={queue.hasLoaded}
        isLoading={queue.isLoading}
        error={queue.error}
        onRetry={queue.refresh}
        onAlertPageChange={(pageNumber) => queue.setRequest({ page: pageNumber, size: queue.request.size })}
        onAlertPageSizeChange={(size) => queue.setRequest({ page: 0, size })}
        onOpenAlert={vi.fn()}
      />
    </>
  );
}

const alertAuthProvider = { kind: "test", getRequestHeaders: () => ({}) };

function alertSession(owner) {
  return { issuer: `issuer-${owner}`, subject: `subject-${owner}` };
}

function queuePage(pageNumber, totalElements, owner) {
  const size = 10;
  const totalPages = Math.ceil(totalElements / size);
  return {
    content: totalElements > 0 && pageNumber < totalPages
      ? [{
        alertId: `alert-${owner}-${pageNumber}`,
        transactionId: `transaction-${owner}-${pageNumber}`,
        customerId: `customer-${owner}-${pageNumber}`,
        alertReason: `reason-${owner}-${pageNumber}`,
        riskLevel: "HIGH",
        alertStatus: "OPEN",
        fraudScore: 0.8,
        alertTimestamp: "2026-11-04T10:00:00Z"
      }]
      : [],
    page: pageNumber,
    size,
    totalElements,
    totalPages
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

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
