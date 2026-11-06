import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Component, useLayoutEffect, useRef, useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AUTHORITIES } from "../auth/session.js";
import { availableDetail } from "../transactions/transactionRiskIntelligenceFixtures.js";
import { WorkspaceRuntimeProvider } from "./WorkspaceRuntimeProvider.jsx";
import { WorkspaceDashboardShell } from "./WorkspaceDashboardShell.jsx";
import { WORKSPACE_ROUTE_REGISTRY } from "./WorkspaceRouteRegistry.jsx";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";

const provider = {
  kind: "demo",
  getRequestHeaders: (session) => ({ "X-Test-Owner": session.issuer })
};
const noOp = () => {};
const route = WORKSPACE_ROUTE_REGISTRY.transactionScoring;
const alertRoute = WORKSPACE_ROUTE_REGISTRY.fraudTransaction;
const AlertRuntime = alertRoute.Runtime;
const PRIVATE_ALERT_DIAGNOSTIC = "customer-55931 Bearer eyJhbGciOiJIUzI1NiJ9 internal-alerts.private \\n at FraudQueueService.java:612 cursor-secret-7f2";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("transaction scoring read boundary with real provider, API client, hooks and views", () => {
  it("does not present the initial empty page as a confirmed zero counter", async () => {
    const pending = deferred();
    installTransport({ list: () => pending.promise.then((response) => response.clone()) });
    const frames = [];
    render(<StatefulWorkspaceShell currentSession={session("a")} frames={frames} />);

    expect(screen.getByRole("link", { name: "Transactions Unavailable" })).toBeInTheDocument();

    await act(async () => pending.resolve(jsonResponse(listPage("a", {}, 0))));
    expect(await screen.findByRole("link", { name: "Transactions 0" })).toBeInTheDocument();
  });

  it("keeps the Transactions navigation count global while the stream is filtered", async () => {
    const transport = installTransport({
      list: (call) => {
        if (call.params.size === "1") return jsonResponse(listPage("a", call.params, 12400));
        return jsonResponse(listPage("a", call.params, call.params.query ? 72 : 12400));
      }
    });
    render(<StatefulWorkspaceShell currentSession={counterSession("a")} frames={[]} />);

    expect(await screen.findByRole("link", { name: "Transactions 12400" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "narrowed" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));

    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Transactions 12400" })).toBeInTheDocument();
    expect(transport.calls.filter((call) => call.kind === "list" && call.params.size === "1")).toHaveLength(1);
    expect(transport.calls.filter((call) => call.kind === "list" && call.params.size === "25" && call.params.query === "narrowed")).toHaveLength(1);
  });

  it.each(["filtered-first", "global-counter-first"])(
    "keeps a failed global count unavailable when the filtered stream resolves %s",
    async (completionOrder) => {
      const globalCounter = deferred();
      const filteredStream = deferred();
      const transport = installTransport({
        list: (call) => {
          if (call.params.size === "1") return globalCounter.promise;
          if (call.params.query === "narrowed") return filteredStream.promise;
          return jsonResponse(listPage("a", call.params, 12400));
        }
      });
      const frames = [];
      render(<StatefulWorkspaceShell currentSession={counterSession("a")} frames={frames} />);

      expect(await screen.findByText("Merchant a")).toBeInTheDocument();
      fireEvent.change(screen.getByLabelText("Search"), { target: { value: "narrowed" } });
      fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
      await waitFor(() => expect(transport.calls.some((call) => call.kind === "list"
        && call.params.size === "25" && call.params.query === "narrowed")).toBe(true));

      const finishGlobalCounterFailure = async () => {
        await act(async () => {
          globalCounter.resolve(jsonResponse({ message: "private counter failure" }, 503));
          await Promise.resolve();
          await Promise.resolve();
        });
      };
      const finishFilteredStream = async () => {
        await act(async () => filteredStream.resolve(jsonResponse(
          listPage("a", { page: "0", size: "25", query: "narrowed" }, 72, "txn-filtered")
        )));
      };

      if (completionOrder === "global-counter-first") {
        await finishGlobalCounterFailure();
        await finishFilteredStream();
      } else {
        await finishFilteredStream();
        await finishGlobalCounterFailure();
      }

      expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
      expect(screen.getAllByText("72", { selector: "strong" }).length).toBeGreaterThan(0);
      expect(screen.getByRole("link", { name: "Transactions Unavailable" })).toBeInTheDocument();
      for (const frame of frames) expect(frame.text).not.toMatch(/Transactions\s*72/);
    }
  );

  it("keeps the previous valid global count as last known after a filtered refresh fails", async () => {
    let globalReads = 0;
    const transport = installTransport({
      list: (call) => {
        if (call.params.size === "1") {
          globalReads += 1;
          return globalReads === 1
            ? jsonResponse(listPage("a", call.params, 12400))
            : jsonResponse({ message: "private counter failure" }, 503);
        }
        return jsonResponse(listPage("a", call.params, call.params.query ? 72 : 12400));
      },
      detail: (call) => call.path.endsWith("/alerts") && call.params.size === "1"
        ? jsonResponse({ message: "private alert counter failure" }, 503)
        : detailFor(call.owner, call.path.split("/").at(-1))
    });
    render(<StatefulWorkspaceShell currentSession={counterSession("a")} frames={[]} />);

    expect(await screen.findByRole("link", { name: "Transactions 12400" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "narrowed" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Retry counters" }));
    await waitFor(() => expect(transport.calls.filter((call) => call.kind === "list" && call.params.size === "1")).toHaveLength(2));

    expect(await screen.findByRole("link", { name: "Transactions 12400 Last known" })).toBeInTheDocument();
    expect(screen.getAllByText("72", { selector: "strong" }).length).toBeGreaterThan(0);
    expect(transport.calls.filter((call) => call.kind === "list" && call.params.size === "1")).toHaveLength(2);
  });

  it("does not let a saved runtime refresh call the next owner's transaction client", async () => {
    const transport = installTransport();
    const frames = [];
    const { rerender } = render(workspace(session("a"), frames));
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    const savedRefreshA = frames.at(-1).result.refreshWorkspace;

    frames.length = 0;
    rerender(workspace(session("b"), frames));
    expect(await screen.findByText("Merchant b")).toBeInTheDocument();
    const ownerBCallsBeforeStaleRefresh = transport.calls.filter((call) => call.owner === "b" && call.kind === "list").length;

    await act(async () => { await savedRefreshA(); });
    expect(transport.calls.filter((call) => call.owner === "b" && call.kind === "list")).toHaveLength(ownerBCallsBeforeStaleRefresh);

    const freshRefreshB = frames.at(-1).result.refreshWorkspace;
    await act(async () => { await freshRefreshB(); });
    await waitFor(() => expect(transport.calls.filter((call) => call.owner === "b" && call.kind === "list")).toHaveLength(ownerBCallsBeforeStaleRefresh + 1));
  });

  it("does not present the initial empty alert page as a confirmed zero counter", async () => {
    const pending = deferred();
    installTransport({ detail: () => pending.promise });
    const frames = [];
    render(<StatefulWorkspaceShell
      currentSession={{ ...session("a"), authorities: [AUTHORITIES.ALERT_READ] }}
      frames={frames}
      initialWorkspacePage="fraudTransaction"
    />);

    expect(screen.getByRole("link", { name: "Alerts Unavailable" })).toBeInTheDocument();

    await act(async () => pending.resolve(jsonResponse({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 })));
    expect(await screen.findByRole("link", { name: "Alerts 0" })).toBeInTheDocument();
  });

  it("resets Alert Queue filter drafts synchronously across owner and capability switches", async () => {
    const pendingA = deferred();
    const pendingB = deferred();
    let aReads = 0;
    const transport = installTransport({
      detail: (call) => {
        if (call.owner === "a") {
          aReads += 1;
          if (aReads === 1) return jsonResponse(alertPage("a", "customer-a-private-97f2"));
          if (aReads === 2) return pendingA.promise;
          return jsonResponse(alertPage("a-return", "customer-a-return"));
        }
        return pendingB.promise;
      }
    });
    const frames = [];
    const sessionA = { ...session("a"), authorities: [AUTHORITIES.ALERT_READ] };
    const { rerender } = render(
      <StatefulWorkspaceShell currentSession={sessionA} frames={frames} initialWorkspacePage="fraudTransaction" />
    );

    expect(await screen.findByText("private-reason-a")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "customer-a-private-97f2" } });
    fireEvent.change(screen.getByLabelText("Risk"), { target: { value: "HIGH" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "OPEN" } });
    expect(screen.getByLabelText("Search")).toHaveValue("customer-a-private-97f2");
    expect(screen.getByLabelText("Risk")).toHaveValue("HIGH");
    expect(screen.getByLabelText("Status")).toHaveValue("OPEN");

    fireEvent.change(screen.getByLabelText("Page size"), { target: { value: "25" } });
    await waitFor(() => expect(transport.calls.filter((call) => call.owner === "a" && call.kind === "detail")).toHaveLength(2));
    expect(screen.getByLabelText("Search")).toHaveValue("customer-a-private-97f2");

    frames.length = 0;
    const sessionB = { ...sessionA, issuer: "b", subject: "subject-b", roles: ["REVIEWER"] };
    rerender(
      <StatefulWorkspaceShell currentSession={sessionB} frames={frames} initialWorkspacePage="fraudTransaction" />
    );

    expectAlertFilterFrames(frames, { Search: "", Risk: "ALL", Status: "ALL" });
    expect(frames.some((frame) => frame.text.includes("customer-a-private-97f2"))).toBe(false);
    await waitFor(() => expect(transport.calls.some((call) => call.owner === "b" && call.kind === "detail")).toBe(true));

    await act(async () => pendingA.resolve(jsonResponse(alertPage("a-late", "customer-a-private-97f2"))));
    expect(screen.getByLabelText("Search")).toHaveValue("");
    expect(screen.queryByText("private-reason-a-late")).not.toBeInTheDocument();
    expectAlertFilterFrames(frames, { Search: "", Risk: "ALL", Status: "ALL" });

    await act(async () => pendingB.resolve(jsonResponse(alertPage("b", "customer-b"))));
    expect(await screen.findByText("private-reason-b")).toBeInTheDocument();
    frames.length = 0;
    rerender(
      <StatefulWorkspaceShell currentSession={sessionA} frames={frames} initialWorkspacePage="fraudTransaction" />
    );

    expectAlertFilterFrames(frames, { Search: "", Risk: "ALL", Status: "ALL" });
    expect(screen.getByLabelText("Search")).toHaveValue("");
    expect(await screen.findByText("private-reason-a-return")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "customer-a-return" } });
    fireEvent.change(screen.getByLabelText("Risk"), { target: { value: "LOW" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "ESCALATED" } });
    const deniedA = { ...sessionA, authorities: [AUTHORITIES.FRAUD_CASE_READ] };

    frames.length = 0;
    rerender(
      <StatefulWorkspaceShell currentSession={deniedA} frames={frames} initialWorkspacePage="fraudTransaction" />
    );
    expectAlertFilterFrames(frames, { Search: "", Risk: "ALL", Status: "ALL" });

    frames.length = 0;
    rerender(
      <StatefulWorkspaceShell currentSession={{ ...sessionA, authorities: [] }} frames={frames} initialWorkspacePage="fraudTransaction" />
    );
    expectAlertFilterFrames(frames, { Search: "", Risk: "ALL", Status: "ALL" });

    frames.length = 0;
    rerender(
      <StatefulWorkspaceShell currentSession={sessionA} frames={frames} initialWorkspacePage="fraudTransaction" />
    );
    expectAlertFilterFrames(frames, { Search: "", Risk: "ALL", Status: "ALL" });
    expect(screen.getByLabelText("Search")).toHaveValue("");
  });

  it("fences one A-to-B-to-A lifecycle across alerts, transactions, counters, details, and callbacks", async () => {
    const pendingAAlerts = deferred();
    const pendingBTransactions = deferred();
    const calls = [];
    let holdNextBTransactionRefresh = false;
    const fetchTransport = vi.fn((url, options) => {
      const parsed = new URL(url, "http://localhost");
      const owner = options.headers["X-Test-Owner"];
      const path = parsed.pathname;
      const params = Object.fromEntries(parsed.searchParams);
      calls.push({ owner, path, params, signal: options.signal });

      if (path.endsWith("/alerts")) {
        if (owner === "a" && params.size === "25") return pendingAAlerts.promise;
        return Promise.resolve(jsonResponse(alertPage(owner)));
      }
      if (path.endsWith("/scored")) {
        if (params.size === "1") {
          return Promise.resolve(jsonResponse(listPage(owner, params, owner === "a" ? 12400 : 9800)));
        }
        if (owner === "b" && params.query === "txn-b-filter" && holdNextBTransactionRefresh) {
          holdNextBTransactionRefresh = false;
          return pendingBTransactions.promise;
        }
        return Promise.resolve(jsonResponse(listPage(owner, params, owner === "a" ? 731 : 72)));
      }
      if (path.includes("/transactions/scored/")) {
        const transactionId = path.split("/").at(-1);
        return Promise.resolve(jsonResponse(detailFor(owner, transactionId)));
      }
      if (path.endsWith("/feedback")) return Promise.resolve(jsonResponse({}, 404));
      return Promise.resolve(jsonResponse({}, 404));
    });
    vi.stubGlobal("fetch", fetchTransport);
    const frames = [];
    const sessionA = counterSession("a");
    const sessionB = counterSession("b");
    const { rerender } = render(
      <IntegratedWorkspaceHarness currentSession={sessionA} workspacePage="fraudTransaction" frames={frames} />
    );

    expect(await screen.findByText("private-reason-a")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("global-transactions")).toHaveTextContent("12400"));
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "private-a-filter" } });
    fireEvent.change(screen.getByLabelText("Risk"), { target: { value: "HIGH" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "OPEN" } });
    fireEvent.change(screen.getByLabelText("Page size"), { target: { value: "25" } });
    await waitFor(() => expect(calls.some((call) => call.owner === "a" && call.path.endsWith("/alerts") && call.params.size === "25")).toBe(true));
    const pendingAAlertCall = calls.find((call) => call.owner === "a" && call.path.endsWith("/alerts") && call.params.size === "25");
    expect(screen.getByLabelText("Search")).toHaveValue("private-a-filter");

    frames.length = 0;
    rerender(<IntegratedWorkspaceHarness currentSession={sessionB} workspacePage="fraudTransaction" frames={frames} />);
    expectAlertFilterFrames(frames, { Search: "", Risk: "ALL", Status: "ALL" });
    expect(frames.some((frame) => frame.text.includes("private-reason-a") || frame.text.includes("private-a-filter"))).toBe(false);
    expect(pendingAAlertCall.signal.aborted).toBe(true);
    expect(await screen.findByText("private-reason-b")).toBeInTheDocument();
    await act(async () => pendingAAlerts.resolve(jsonResponse({ ...alertPage("a-late"), size: 25 })));
    expect(screen.queryByText("private-reason-a-late")).not.toBeInTheDocument();

    frames.length = 0;
    rerender(<IntegratedWorkspaceHarness currentSession={sessionB} workspacePage="transactionScoring" frames={frames} />);
    expect(await screen.findByText("Merchant b")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("global-transactions")).toHaveTextContent("9800"));
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "txn-b-filter" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(await screen.findByText("txn-b")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(await screen.findByRole("region", { name: "Merchant Investigation" })).toHaveTextContent("merchant-b");
    const savedRefreshB = frames.at(-1).result.refreshWorkspace;
    holdNextBTransactionRefresh = true;
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(calls.some((call) => call.owner === "b" && call.path.endsWith("/scored") && call.params.query === "txn-b-filter" && call.signal && !call.signal.aborted)).toBe(true));
    const pendingBTransactionCall = calls.filter((call) => call.owner === "b" && call.path.endsWith("/scored") && call.params.query === "txn-b-filter").at(-1);

    frames.length = 0;
    rerender(<IntegratedWorkspaceHarness currentSession={sessionA} workspacePage="transactionScoring" frames={frames} />);
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    expect(pendingBTransactionCall.signal.aborted).toBe(true);
    expect(frames.every((frame) => !frame.text.includes("Merchant b") && !frame.text.includes("txn-b-filter"))).toBe(true);
    await act(async () => pendingBTransactions.resolve(jsonResponse(listPage("b-late", { page: "0", size: "25" }, 2, "txn-b-late"))));
    expect(screen.queryByText("txn-b-late")).not.toBeInTheDocument();
    const aTransactionCallsBeforeStaleRefresh = calls.filter((call) => call.owner === "a" && call.path.endsWith("/scored") && call.params.size === "25").length;
    await act(async () => { await savedRefreshB(); });
    expect(calls.filter((call) => call.owner === "a" && call.path.endsWith("/scored") && call.params.size === "25")).toHaveLength(aTransactionCallsBeforeStaleRefresh);
    await waitFor(() => expect(screen.getByTestId("global-transactions")).toHaveTextContent("12400"));

    const deniedA = { ...sessionA, authorities: [AUTHORITIES.ALERT_READ] };
    rerender(<IntegratedWorkspaceHarness currentSession={deniedA} workspacePage="transactionScoring" frames={frames} />);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByTestId("global-transactions")).toHaveTextContent("Unavailable");

    rerender(<IntegratedWorkspaceHarness currentSession={sessionA} workspacePage="fraudTransaction" frames={frames} />);
    expect(await screen.findByText("private-reason-a")).toBeInTheDocument();
    expect(screen.getByLabelText("Search")).toHaveValue("");
    expect(screen.getByLabelText("Risk")).toHaveValue("ALL");
    expect(screen.getByLabelText("Status")).toHaveValue("ALL");
    await waitFor(() => expect(screen.getByTestId("global-transactions")).toHaveTextContent("12400"));
  });

  it("preserves Alert Queue filter drafts across a same-owner manual refresh", async () => {
    const pendingRefresh = deferred();
    let alertReads = 0;
    const transport = installTransport({
      detail: () => (++alertReads === 1
        ? jsonResponse(alertPage("a", "customer-a"))
        : pendingRefresh.promise)
    });
    const frames = [];
    render(<AlertWorkspaceRuntime currentSession={{ ...session("a"), authorities: [AUTHORITIES.ALERT_READ] }} frames={frames} />);

    expect(await screen.findByText("private-reason-a")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "customer-a" } });
    fireEvent.change(screen.getByLabelText("Risk"), { target: { value: "HIGH" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "OPEN" } });

    const refreshWorkspace = frames.at(-1).result.refreshWorkspace;
    frames.length = 0;
    await act(async () => { await refreshWorkspace(); });
    await waitFor(() => expect(transport.calls.filter((call) => call.kind === "detail")).toHaveLength(2));
    expectAlertFilterFrames(frames, { Search: "customer-a", Risk: "HIGH", Status: "OPEN" });

    await act(async () => pendingRefresh.resolve(jsonResponse(alertPage("a-refreshed", "customer-a"))));
    expect(await screen.findByText("private-reason-a-refreshed")).toBeInTheDocument();
    expectAlertFilterFrames(frames, { Search: "customer-a", Risk: "HIGH", Status: "OPEN" });
  });

  it.each([
    ["HTTP 204", () => new Response(null, { status: 204 })],
    ["HTTP 200 JSON null", () => jsonResponse(null)],
    ["missing page envelope", () => jsonResponse({ message: "private malformed backend detail" })],
    ["null content", () => jsonResponse({ content: null, totalElements: 0, totalPages: 0, page: 0, size: 10 })],
    ["non-array content", () => jsonResponse({ content: {}, totalElements: 0, totalPages: 0, page: 0, size: 10 })],
    ["null alert row", () => jsonResponse({ content: [null], totalElements: 1, totalPages: 1, page: 0, size: 10 })],
    ["array alert row", () => malformedAlertResponse([])],
    ["object alert reason", () => malformedAlertResponse({ alertReason: { private: "private malformed row detail" } })],
    ["object customer ID", () => malformedAlertResponse({ customerId: { private: "private malformed row detail" } })],
    ["invalid alert ID", () => malformedAlertResponse({ alertId: " " })],
    ["invalid fraud score", () => malformedAlertResponse({ fraudScore: 1.1 })],
    ["invalid alert timestamp", () => malformedAlertResponse({ alertTimestamp: "not-a-date" })],
    ["impossible alert timestamp", () => malformedAlertResponse({ alertTimestamp: "2026-02-30T12:00:00Z" })],
    ["malformed risk enum", () => malformedAlertResponse({ riskLevel: {} })],
    ["unknown status enum", () => malformedAlertResponse({ alertStatus: "PENDING" })],
    ["wrong response page", () => jsonResponse({ content: [], totalElements: 0, totalPages: 0, page: 1, size: 10 })],
    ["wrong response size", () => jsonResponse({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 25 })]
  ])("fails closed on %s through the real alert workspace", async (_caseName, responseFactory) => {
    const transport = installTransport({ detail: () => responseFactory() });
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    try {
      render(
        <RenderFailureBoundary>
          <StatefulWorkspaceShell
            currentSession={{ ...session("a"), authorities: [AUTHORITIES.ALERT_READ] }}
            frames={[]}
            initialWorkspacePage="fraudTransaction"
          />
        </RenderFailureBoundary>
      );

      await waitFor(() => {
        expect(screen.queryByTestId("render-failure")).not.toBeInTheDocument();
        expect(screen.getByRole("alert")).toHaveTextContent("Alerts are temporarily unavailable. Please retry.");
      });
      expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Alerts Unavailable" })).toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "Alerts 0" })).not.toBeInTheDocument();
      expect(screen.queryByText("No alerts on this loaded page match the local filters.")).not.toBeInTheDocument();
      expect(screen.queryByText("private malformed backend detail")).not.toBeInTheDocument();
      expect(document.body.textContent).not.toContain("private malformed row detail");
      expect(transport.calls.some((call) => call.path.endsWith("/alerts"))).toBe(true);
      fireEvent.click(screen.getByRole("button", { name: "Try again" }));
      await waitFor(() => expect(transport.calls.filter((call) => call.path.endsWith("/alerts"))).toHaveLength(2));
      expect(screen.getByRole("alert")).toHaveTextContent("Alerts are temporarily unavailable. Please retry.");
    } finally {
      consoleError.mockRestore();
    }
  });

  it("renders historical alert summaries with nullable optional fields", async () => {
    const historicalRow = {
      alertId: "alert-historical",
      transactionId: null,
      customerId: null,
      riskLevel: null,
      fraudScore: null,
      alertStatus: null,
      alertReason: null,
      alertTimestamp: null
    };
    installTransport({ detail: () => jsonResponse({ ...alertPage("historical"), content: [historicalRow] }) });

    render(
      <RenderFailureBoundary>
        <StatefulWorkspaceShell
          currentSession={{ ...session("a"), authorities: [AUTHORITIES.ALERT_READ] }}
          frames={[]}
          initialWorkspacePage="fraudTransaction"
        />
      </RenderFailureBoundary>
    );

    expect(await screen.findByText("UNKNOWN")).toBeInTheDocument();
    expect(screen.getByText("N/A")).toBeInTheDocument();
    expect(screen.getByText("Not available")).toBeInTheDocument();
    expect(screen.queryByTestId("render-failure")).not.toBeInTheDocument();
    expect(screen.queryByText("null")).not.toBeInTheDocument();
  });

  it.each([
    ["expired session", () => privateApiFailure(401, "session_expired", ["reason:invalid_jwt"]), "Session expired"],
    ["generic unauthorized response", () => privateApiFailure(401, "private-auth-code", ["private detail"]), "Session required"],
    ["insufficient authority", () => privateApiFailure(403, "private-forbidden-code", ["reason:insufficient_authority"]), "Access denied"],
    ["not found", () => privateApiFailure(404), "Unable to load data"],
    ["rate limit", () => privateApiFailure(429), "Unable to load data"],
    ["server failure", () => privateApiFailure(500), "Unable to load data"],
    ["service unavailable", () => privateApiFailure(503), "Unable to load data"],
    ["malformed JSON", () => new Response("{malformed", { headers: { "Content-Type": "application/json" } }), "Unable to load data"],
    ["network failure", () => Promise.reject(new TypeError(PRIVATE_ALERT_DIAGNOSTIC)), "Unable to load data"]
  ])("sanitizes Alert Queue %s failures and keeps retry available", async (_caseName, responseFactory, expectedHeading) => {
    const transport = installTransport({ detail: () => responseFactory() });
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const consoleWarn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const consoleLog = vi.spyOn(console, "log").mockImplementation(() => {});

    try {
      render(
        <RenderFailureBoundary>
          <StatefulWorkspaceShell
            currentSession={{ ...session("a"), authorities: [AUTHORITIES.ALERT_READ] }}
            frames={[]}
            initialWorkspacePage="fraudTransaction"
          />
        </RenderFailureBoundary>
      );

      expect(await screen.findByRole("heading", { name: expectedHeading })).toBeInTheDocument();
      expect(screen.queryByTestId("render-failure")).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
      const renderedDom = document.body.innerHTML;
      for (const secret of ["customer-55931", "eyJhbGciOiJIUzI1NiJ9", "internal-alerts.private", "FraudQueueService.java:612", "cursor-secret-7f2"]) {
        expect(renderedDom).not.toContain(secret);
      }
      const consoleOutput = JSON.stringify([
        ...consoleError.mock.calls,
        ...consoleWarn.mock.calls,
        ...consoleLog.mock.calls
      ]);
      expect(consoleOutput).not.toContain("customer-55931");
      expect(consoleOutput).not.toContain("eyJhbGciOiJIUzI1NiJ9");
      expect(consoleOutput).not.toContain("internal-alerts.private");
      expect(consoleOutput).not.toContain("FraudQueueService.java:612");
      expect(consoleOutput).not.toContain("cursor-secret-7f2");

      fireEvent.click(screen.getByRole("button", { name: "Try again" }));
      await waitFor(() => expect(transport.calls.filter((call) => call.path.endsWith("/alerts"))).toHaveLength(2));
      expect(screen.getByRole("heading", { name: expectedHeading })).toBeInTheDocument();
    } finally {
      consoleError.mockRestore();
      consoleWarn.mockRestore();
      consoleLog.mockRestore();
    }
  });

  it("clears the prior alert page in the first commit after an owner change", async () => {
    const pendingB = deferred();
    const transport = installTransport({
      detail: (call) => call.owner === "a" ? jsonResponse(alertPage("a")) : pendingB.promise
    });
    const frames = [];
    const sessionA = { ...session("a"), authorities: [AUTHORITIES.ALERT_READ] };
    const { rerender } = render(<StatefulWorkspaceShell
      currentSession={sessionA}
      frames={frames}
      initialWorkspacePage="fraudTransaction"
    />);
    expect(await screen.findByText("private-reason-a")).toBeInTheDocument();

    frames.length = 0;
    rerender(<StatefulWorkspaceShell
      currentSession={{ ...sessionA, issuer: "b", subject: "subject-b" }}
      frames={frames}
      initialWorkspacePage="fraudTransaction"
    />);

    expect(frames.length).toBeGreaterThan(0);
    for (const frame of frames) expect(frame.text).not.toContain("private-reason-a");
    expect(screen.queryByText("private-reason-a")).not.toBeInTheDocument();
    expect(screen.getByText("Loading fraud alerts...")).toBeInTheDocument();
    await waitFor(() => expect(transport.calls.some((call) => call.owner === "b" && call.kind === "detail")).toBe(true));

    await act(async () => pendingB.resolve(jsonResponse(alertPage("b"))));
    expect(await screen.findByText("private-reason-b")).toBeInTheDocument();
  });

  it("ignores a late alert-page response from the previous owner", async () => {
    const pendingA = deferred();
    let aReads = 0;
    const transport = installTransport({
      detail: (call) => call.owner === "a"
        ? ++aReads === 1 ? jsonResponse(alertPage("a")) : pendingA.promise
        : jsonResponse(alertPage("b"))
    });
    const frames = [];
    const sessionA = { ...session("a"), authorities: [AUTHORITIES.ALERT_READ] };
    const { rerender } = render(<StatefulWorkspaceShell
      currentSession={sessionA}
      frames={frames}
      initialWorkspacePage="fraudTransaction"
    />);
    expect(await screen.findByText("private-reason-a")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Page size"), { target: { value: "25" } });
    await waitFor(() => expect(transport.calls.filter((call) => call.owner === "a" && call.kind === "detail")).toHaveLength(2));

    frames.length = 0;
    rerender(<StatefulWorkspaceShell
      currentSession={{ ...sessionA, issuer: "b", subject: "subject-b" }}
      frames={frames}
      initialWorkspacePage="fraudTransaction"
    />);
    expect(await screen.findByText("private-reason-b")).toBeInTheDocument();

    await act(async () => pendingA.resolve(jsonResponse(alertPage("a-late"))));
    expect(screen.getByText("private-reason-b")).toBeInTheDocument();
    expect(screen.queryByText("private-reason-a-late")).not.toBeInTheDocument();
  });

  it.each([false, undefined])("closes loaded rows, expanded merchant and controls in every commit when permission becomes %s", async (capability) => {
    const transport = installTransport();
    const frames = [];
    const { rerender } = render(workspace(session("a"), frames));
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Details" })[0]);
    expect(await screen.findByRole("region", { name: "Merchant Investigation" })).toHaveTextContent("merchant-a");
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "private-draft-a" } });
    const savedRefresh = frames.at(-1).result.refreshWorkspace;
    expect(transport.calls.filter((call) => call.kind === "detail")).toHaveLength(1);

    frames.length = 0;
    rerender(workspace(session("b", capability === undefined ? null : capability), frames));

    expectNoA(frames);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Details" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Search")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh" })).toBeDisabled();
    await act(async () => savedRefresh());
    expect(transport.calls.filter((call) => call.kind === "list")).toHaveLength(1);
  });

  it("isolates an authorized same-user issuer/subject/role change before the new HTTP read resolves", async () => {
    const next = deferred();
    const transport = installTransport({ list: (call) => call.owner === "b" ? next.promise : jsonResponse(listPage("a", call.params)) });
    const frames = [];
    const a = session("a");
    const { rerender } = render(workspace(a, frames));
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Details" })[0]);
    expect(await screen.findByRole("region", { name: "Merchant Investigation" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "private-draft-a" } });

    frames.length = 0;
    rerender(workspace({
      ...a, issuer: "b", subject: "subject-b", roles: ["REVIEWER"],
      authorities: [AUTHORITIES.TRANSACTION_MONITOR_READ, AUTHORITIES.ALERT_READ]
    }, frames));

    expectNoA(frames);
    expect(screen.getByLabelText("Search")).toHaveValue("");
    expect(screen.getByText("Loading scored transactions...")).toBeInTheDocument();
    await act(async () => next.resolve(jsonResponse(listPage("b", {}, 2, "txn-a"))));
    expect(await screen.findByText("Merchant b")).toBeInTheDocument();
    expect(screen.queryByText("Merchant a")).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Details" })[0]);
    expect(await screen.findByRole("region", { name: "Merchant Investigation" })).toHaveTextContent("merchant-b");
    expect(transport.calls.filter((call) => call.kind === "list").map((call) => call.owner)).toEqual(["a", "b"]);
    expect(transport.calls.filter((call) => call.kind === "detail").map((call) => call.owner)).toEqual(["a", "b"]);
  });

  it.each(["late success", "late failure", "abort respected"])("keeps the new owner after old HTTP completion: %s", async (completion) => {
    const old = deferred();
    const next = deferred();
    let aReads = 0;
    const transport = installTransport({
      list: (call) => {
        if (call.owner === "b") return next.promise;
        if (++aReads === 1) return jsonResponse(listPage("a", call.params));
        return completion === "abort respected" ? withAbort(old.promise, call.signal) : old.promise;
      }
    });
    const frames = [];
    const { rerender } = render(workspace(session("a"), frames));
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    const oldCall = transport.calls.filter((call) => call.kind === "list").at(-1);
    frames.length = 0;
    rerender(workspace(session("b"), frames));
    expect(oldCall.signal.aborted).toBe(true);
    expectNoA(frames);

    if (completion === "late failure") {
      await act(async () => old.reject(new Error("private old exception")));
      expect(screen.getByText("Loading scored transactions...")).toBeInTheDocument();
      expect(frames.at(-1).error).toBeNull();
    }
    await act(async () => next.resolve(jsonResponse(listPage("b", {}, 2))));
    expect(await screen.findByText("Merchant b")).toBeInTheDocument();
    if (completion !== "late failure") {
      await act(async () => old.resolve(jsonResponse(listPage("a"))));
    }
    expect(screen.getByText("Merchant b")).toBeInTheDocument();
    expectNoA(frames);
  });

  it("revokes access with both an old list request and an expanded detail request still pending", async () => {
    const list = deferred();
    const detail = deferred();
    let reads = 0;
    const transport = installTransport({
      list: (call) => ++reads === 1 ? jsonResponse(listPage("a", call.params)) : list.promise,
      detail: () => detail.promise
    });
    const frames = [];
    const { rerender } = render(workspace(session("a"), frames));
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Details" })[0]);
    await waitFor(() => expect(transport.calls.some((call) => call.kind === "detail")).toBe(true));
    await act(async () => { void frames.at(-1).result.refreshWorkspace(); });
    frames.length = 0;
    rerender(workspace(session("b", false), frames));

    expectNoA(frames);
    expect(transport.calls.filter((call) => call.kind === "list").at(-1).signal.aborted).toBe(true);
    expect(transport.calls.find((call) => call.kind === "detail").signal.aborted).toBe(true);
    await act(async () => {
      list.resolve(jsonResponse(listPage("a")));
      detail.resolve(jsonResponse(detailFor("a", "txn-a")));
    });
    expectNoA(frames);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("retains filters, pagination, size and manual refresh while fencing reverse-order filtered results", async () => {
    const second = deferred();
    const third = deferred();
    const transport = installTransport({
      list: (call) => {
        if (call.params.query === "second") return second.promise;
        if (call.params.query === "third" && call.params.size === "25") return third.promise;
        return jsonResponse(listPage(call.params.query || "a", call.params, 73));
      }
    });
    const frames = [];
    render(workspace(session("a"), frames));
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    expect(transport.calls.filter((call) => call.kind === "detail")).toHaveLength(0);
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "second" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "third" } });
    fireEvent.change(screen.getByLabelText("Alert recommendation"), { target: { value: "SUSPICIOUS" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(screen.queryByText("Merchant a")).not.toBeInTheDocument();

    await act(async () => third.resolve(jsonResponse(listPage("third", {}, 73))));
    await act(async () => second.resolve(jsonResponse(listPage("second", {}, 73))));
    expect(screen.getByText("Merchant third")).toBeInTheDocument();
    expect(screen.queryByText("Merchant second")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Page size"), { target: { value: "10" } });
    await waitFor(() => expect(screen.getByLabelText("Page size")).toHaveValue("10"));
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(screen.getByText("Page 2 of 8")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(transport.calls.filter((call) => call.kind === "list")).toHaveLength(6));
    expect(transport.calls.filter((call) => call.kind === "list").at(-1).params).toMatchObject({
      query: "third", classification: "SUSPICIOUS", page: "1", size: "10"
    });
    expect(screen.getByLabelText("Search")).toHaveValue("third");
    expect(transport.calls.filter((call) => call.kind === "detail")).toHaveLength(0);
  });

  it.each([401, 403, 404, 500, 503, "network", "invalid"])("drops previous rows for %s without exposing transport exceptions", async (failure) => {
    let fail = false;
    installTransport({
      list: (call) => {
        if (!fail) return jsonResponse(listPage("a", call.params));
        if (failure === "network") return Promise.reject(new Error("private network token"));
        if (failure === "invalid") return jsonResponse({ content: [null] });
        return jsonResponse({ message: "private API secret" }, failure);
      }
    });
    const frames = [];
    render(workspace(session("a"), frames));
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    fail = true;
    frames.length = 0;
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect(frames.at(-1).error).toBeTruthy());
    expectNoA(frames);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain("private API secret");
    expect(document.body.textContent).not.toContain("private network token");
  });

  it("makes the page unavailable without a runtime client", () => {
    const transport = installTransport();
    render(workspace(session("a"), [], { enabled: false }));
    expect(screen.getByText("Transaction reads unavailable")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh" })).toBeDisabled();
    expect(transport.calls).toHaveLength(0);
  });

  it.each([false, true])("renders only displayable merchant fields in both table and detail, valid mix: %s", async (mixed) => {
    const merchantInfo = {
      merchantName: mixed ? "<img src=x onerror=merchant()>" : "x".repeat(129),
      merchantId: mixed ? "merchant-displayable" : { privateId: "must-stay-hidden" },
      merchantCategory: ["blocked-category"],
      channel: "blocked\u202e",
      acquiringCountryCode: 123,
      attributes: { privateMetadata: { email: "hidden@example.test" }, tokenHint: "hidden-token-value" }
    };
    installTransport({
      list: (call) => {
        const page = listPage("safe", call.params);
        page.content[0].merchantInfo = merchantInfo;
        return jsonResponse(page);
      },
      detail: () => jsonResponse(availableDetail({ transactionId: "txn-safe", merchantInfo }))
    });
    render(workspace(session("safe"), []));
    expect(await screen.findByRole("table")).toBeInTheDocument();
    expect(screen.getByText("Unknown channel")).toBeInTheDocument();
    if (!mixed) expect(screen.getByText("Unknown merchant")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    const merchant = await screen.findByRole("region", { name: "Merchant Investigation" });
    if (mixed) {
      expect(merchant).toHaveTextContent("<img src=x onerror=merchant()>");
      expect(merchant).toHaveTextContent("merchant-displayable");
    } else {
      expect(merchant).toHaveTextContent("No displayable transaction merchant details are available.");
    }
    expect(document.querySelector("img")).toBeNull();
    for (const hidden of ["blocked-category", "must-stay-hidden", "hidden@example.test", "hidden-token-value", "x".repeat(129)]) {
      expect(document.body.textContent).not.toContain(hidden);
    }
  });

  it("aborts on unmount and cannot expose an old result through a later session", async () => {
    const old = deferred();
    const transport = installTransport({
      list: (call) => call.owner === "a" ? old.promise : jsonResponse(listPage("b", call.params, 2))
    });
    const first = render(workspace(session("a"), []));
    const signal = transport.calls[0].signal;
    first.unmount();
    expect(signal.aborted).toBe(true);
    const frames = [];
    render(workspace(session("b"), frames));
    expect(await screen.findByText("Merchant b")).toBeInTheDocument();
    await act(async () => old.resolve(jsonResponse(listPage("a"))));
    expectNoA(frames);
  });

  it("does not retain the old transaction counter in the real dashboard shell's first new-session commit", async () => {
    const next = deferred();
    installTransport({
      list: (call) => call.owner === "a"
        ? jsonResponse(listPage("a", call.params, call.params.size === "1" ? 9404 : 731))
        : next.promise
    });
    const frames = [];
    function shell(currentSession) {
      return (
        <WorkspaceRuntimeProvider session={currentSession} authProvider={provider}>
          <ShellCommitProbe frames={frames}>
            <WorkspaceDashboardShell workspacePage="transactionScoring" sessionState={{ status: "authenticated" }} />
          </ShellCommitProbe>
        </WorkspaceRuntimeProvider>
      );
    }
    const { rerender } = render(shell(session("a")));
    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    await waitFor(() => expect(document.querySelector(".workspaceTabActive")).toHaveTextContent("9404"));
    frames.length = 0;
    rerender(shell(session("b")));
    expect(frames.length).toBeGreaterThan(0);
    for (const text of frames) {
      expect(text).not.toContain("9404");
      expect(text).not.toContain("Merchant a");
    }
  });

  it.each(["transactionScoring", "shadowPerformance"])(
    "redacts all old shared counter families in the first %s commit after a same-user owner change",
    async (workspacePage) => {
      const transport = installCounterTransport();
      const frames = [];
      function shell(currentSession) {
        return (
          <WorkspaceRuntimeProvider session={currentSession} authProvider={provider}>
            <ShellCommitProbe frames={frames}>
              <WorkspaceDashboardShell
                workspacePage={workspacePage}
                clearSelection={noOp}
                navigateWorkspace={noOp}
                sessionState={{ status: "authenticated" }}
              />
            </ShellCommitProbe>
          </WorkspaceRuntimeProvider>
        );
      }

      const { rerender } = render(shell(counterSession("a")));
      await waitFor(() => {
        const navigation = document.querySelector(".workspaceTabs");
        expect(navigation?.textContent).toContain("9101");
        expect(navigation?.textContent).toContain("9202");
        expect(navigation?.textContent).toContain("9303");
        expect(navigation?.textContent).toContain("9404");
      });
      if (workspacePage === "transactionScoring") {
        expect(await screen.findByText("Merchant a")).toBeInTheDocument();
      }

      frames.length = 0;
      rerender(shell(counterSession("b")));
      expect(frames.length).toBeGreaterThan(0);
      for (const text of frames) {
        for (const ownerData of ["9101", "9202", "9303", "9404"]) {
          expect(text).not.toContain(ownerData);
        }
      }
      expect(transport.calls.some((call) => call.owner === "b")).toBe(true);
    }
  );

  it("isolates pending A transaction reads across shell navigation, B return, filters and permission revocation", async () => {
    const transport = installLifecycleTransport();
    const frames = [];
    const { rerender } = render(<StatefulWorkspaceShell currentSession={counterSession("a")} frames={frames} />);

    expect(await screen.findByText("Merchant a")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("navigation", { name: "Analyst workspace sections" })).toHaveTextContent("9101");
      expect(screen.getByRole("navigation", { name: "Analyst workspace sections" })).toHaveTextContent("9202");
      expect(screen.getByRole("navigation", { name: "Analyst workspace sections" })).toHaveTextContent("9303");
      expect(screen.getByRole("navigation", { name: "Analyst workspace sections" })).toHaveTextContent("9404");
    });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    await waitFor(() => expect(transport.calls.some((call) => call.owner === "a" && call.kind === "detail")).toBe(true));
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "private-draft-a" } });
    const aListCallsBeforeRefresh = transport.calls.filter((call) => call.owner === "a" && call.kind === "list" && call.params.size === "25").length;
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(transport.calls.filter((call) => call.owner === "a" && call.kind === "list" && call.params.size === "25").length)
      .toBeGreaterThan(aListCallsBeforeRefresh));
    const pendingAListCall = transport.calls.filter((call) => call.owner === "a" && call.kind === "list" && call.params.size === "25").at(-1);
    const pendingADetailCall = transport.calls.find((call) => call.owner === "a" && call.kind === "detail");

    fireEvent.click(screen.getByRole("link", { name: /Model evidence/ }));
    await waitFor(() => expect(document.querySelector(".workspaceTabActive")).toHaveTextContent("Model evidence"));
    frames.length = 0;
    rerender(<StatefulWorkspaceShell currentSession={counterSession("b")} frames={frames} />);

    expectCommittedFramesHaveNoOwnerA(frames);
    expect(pendingAListCall.signal.aborted).toBe(true);
    expect(pendingADetailCall.signal.aborted).toBe(true);
    await waitFor(() => expect(transport.calls.some((call) => call.owner === "b")).toBe(true));

    await act(async () => transport.pendingADetail.resolve(jsonResponse(detailFor("a", "txn-a"))));
    expectCommittedFramesHaveNoOwnerA(frames);
    await act(async () => transport.pendingAList.reject(new Error("private A transport failure")));
    expectCommittedFramesHaveNoOwnerA(frames);

    fireEvent.click(screen.getByRole("link", { name: /Transactions/ }));
    expect(await screen.findByText("Merchant b")).toBeInTheDocument();
    expect(screen.getByLabelText("Search")).toHaveValue("");
    expect(transport.calls.filter((call) => call.owner === "b" && call.kind === "detail")).toHaveLength(0);
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "B-query" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(transport.calls.some((call) => call.owner === "b" && call.kind === "list" && call.params.query === "B-query")).toBe(true));
    expect(await screen.findByText("txn-b-B-query-0")).toBeInTheDocument();
    expectCommittedFramesHaveNoOwnerA(frames);
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(screen.getByText("txn-b-B-query-1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(await screen.findByRole("region", { name: "Merchant Investigation" })).toHaveTextContent("merchant-b");
    expect(transport.calls.filter((call) => call.owner === "b" && call.kind === "detail")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(transport.calls.filter((call) => call.owner === "b" && call.kind === "list" && call.params.size === "25").length).toBeGreaterThanOrEqual(4));
    expect(transport.calls.filter((call) => call.owner === "b" && call.kind === "list" && call.params.query === "B-query").at(-1).params)
      .toMatchObject({ query: "B-query", page: "1", size: "25" });

    transport.holdNextBList = true;
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(transport.pendingBList).toBeDefined());
    const pendingBSignal = transport.calls.filter((call) => call.owner === "b" && call.kind === "list" && call.params.size === "25").at(-1).signal;
    const callsBeforeRevoke = transport.calls.length;
    frames.length = 0;
    rerender(<StatefulWorkspaceShell currentSession={counterSession("b", false)} frames={frames} />);

    expectCommittedFramesHaveNoOwnerA(frames);
    expect(pendingBSignal.aborted).toBe(true);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(transport.calls.slice(callsBeforeRevoke).some((call) => call.owner === "b" && call.path.includes("transactions/scored"))).toBe(false);
    await act(async () => transport.pendingBList.resolve(jsonResponse(listPage("b", {}, 51, "txn-b-late"))));
    expectCommittedFramesHaveNoOwnerA(frames);
    expect(screen.queryByText("txn-b-late")).not.toBeInTheDocument();
  });
});

function workspace(currentSession, frames, props = {}) {
  const Runtime = route.Runtime;
  return (
    <WorkspaceRuntimeProvider session={currentSession} authProvider={provider} {...props}>
      <Runtime route={route} sharedWorkspaceReadsEnabled setCounterValue={noOp}>
        {(result) => <ReadCommitProbe result={result} frames={frames} />}
      </Runtime>
    </WorkspaceRuntimeProvider>
  );
}

function ReadCommitProbe({ result, frames }) {
  const ref = useRef(null);
  useLayoutEffect(() => {
    frames.push({
      text: ref.current.textContent,
      inputs: Array.from(ref.current.querySelectorAll("input")).map((node) => node.value),
      page: result.navigationState.transactionPage, error: result.error, result
    });
  });
  return <div ref={ref}>{result.workspaceContent}</div>;
}

function ShellCommitProbe({ frames, children }) {
  const ref = useRef(null);
  useLayoutEffect(() => { frames.push(ref.current.textContent); });
  return <div ref={ref}>{children}</div>;
}

class RenderFailureBoundary extends Component {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? <div data-testid="render-failure">Workspace render failed</div> : this.props.children;
  }
}

function StatefulWorkspaceShell({ currentSession, frames, initialWorkspacePage = "transactionScoring" }) {
  const [workspacePage, setWorkspacePage] = useState(initialWorkspacePage);
  return (
    <WorkspaceRuntimeProvider session={currentSession} authProvider={provider}>
      <WorkspaceLifecycleCommitProbe frames={frames}>
        <WorkspaceDashboardShell
          workspacePage={workspacePage}
          navigateWorkspace={setWorkspacePage}
          clearSelection={noOp}
          sessionState={{ status: "authenticated" }}
        />
      </WorkspaceLifecycleCommitProbe>
    </WorkspaceRuntimeProvider>
  );
}

function AlertWorkspaceRuntime({ currentSession, frames }) {
  return (
    <WorkspaceRuntimeProvider session={currentSession} authProvider={provider}>
      <AlertRuntime
        route={alertRoute}
        sharedWorkspaceReadsEnabled
        setCounterValue={noOp}
        onOpenAlert={noOp}
      >
        {(result) => <WorkspaceRuntimeCommitProbe result={result} frames={frames} />}
      </AlertRuntime>
    </WorkspaceRuntimeProvider>
  );
}

function IntegratedWorkspaceHarness({ currentSession, workspacePage, frames }) {
  return (
    <WorkspaceRuntimeProvider session={currentSession} authProvider={provider}>
      <IntegratedWorkspaceRuntime workspacePage={workspacePage} frames={frames} />
    </WorkspaceRuntimeProvider>
  );
}

function IntegratedWorkspaceRuntime({ workspacePage, frames }) {
  const counters = useWorkspaceCounters({
    enabled: true,
    includeAlerts: false,
    includeFraudCases: false,
    includeSuspiciousTransactions: false,
    includeTransactions: true
  });
  const routeForWorkspace = WORKSPACE_ROUTE_REGISTRY[workspacePage];
  const Runtime = routeForWorkspace.Runtime;

  return (
    <>
      <output data-testid="global-transactions">{counters.counters.transactions ?? "Unavailable"}</output>
      <Runtime
        route={routeForWorkspace}
        sharedWorkspaceReadsEnabled
        setCounterValue={counters.setCounterValue}
        onOpenAlert={noOp}
      >
        {(result) => <WorkspaceRuntimeCommitProbe result={result} frames={frames} />}
      </Runtime>
    </>
  );
}

function WorkspaceRuntimeCommitProbe({ result, frames }) {
  const ref = useRef(null);
  useLayoutEffect(() => {
    frames.push({ ...workspaceFrame(ref.current), result });
  });
  return <div ref={ref}>{result.workspaceContent}</div>;
}

function WorkspaceLifecycleCommitProbe({ frames, children }) {
  const ref = useRef(null);
  useLayoutEffect(() => { frames.push(workspaceFrame(ref.current)); });
  return <div ref={ref}>{children}</div>;
}

function workspaceFrame(root) {
  return {
    text: root.textContent,
    inputs: Array.from(root.querySelectorAll("input")).map((node) => node.value),
    controls: Object.fromEntries(Array.from(root.querySelectorAll("label")).flatMap((label) => {
      const control = label.querySelector("input, select");
      if (!control) return [];
      const name = label.childNodes[0]?.textContent?.trim();
      return name ? [[name, control.value]] : [];
    }))
  };
}

function expectNoA(frames) {
  expect(frames.length).toBeGreaterThan(0);
  for (const frame of frames) {
    for (const value of ["Merchant a", "merchant-a", "txn-a", "customer-a", "731"]) {
      expect(frame.text).not.toContain(value);
      expect(JSON.stringify(frame.page)).not.toContain(value);
    }
    expect(frame.inputs).not.toContain("private-draft-a");
  }
}

function expectCommittedFramesHaveNoOwnerA(frames) {
  expect(frames.length).toBeGreaterThan(0);
  const oldOwnerValues = [
    "Merchant a", "merchant-a", "txn-a", "customer-a", "private-draft-a",
    "private A transport failure", "9101", "9202", "9303", "731", "9404"
  ];
  for (const frame of frames) {
    for (const value of oldOwnerValues) {
      expect(frame.text).not.toContain(value);
      expect(frame.inputs).not.toContain(value);
    }
  }
}

function expectAlertFilterFrames(frames, expected) {
  expect(frames.length).toBeGreaterThan(0);
  for (const frame of frames) {
    expect(frame.controls).toMatchObject(expected);
  }
}

function installTransport({ list = (call) => jsonResponse(listPage(call.owner, call.params)), detail = (call) => jsonResponse(detailFor(call.owner, call.path.split("/").at(-1))) } = {}) {
  const calls = [];
  const fetchTransport = vi.fn((url, options) => {
    const parsed = new URL(url, "http://localhost");
    const path = parsed.pathname;
    const kind = path.endsWith("/feedback") ? "feedback" : path.endsWith("/scored") ? "list" : "detail";
    const call = { kind, owner: options.headers["X-Test-Owner"], path, params: Object.fromEntries(parsed.searchParams), signal: options.signal };
    calls.push(call);
    if (kind === "feedback") return Promise.resolve(jsonResponse({}, 404));
    return Promise.resolve(kind === "list" ? list(call) : detail(call));
  });
  vi.stubGlobal("fetch", fetchTransport);
  return { calls };
}

function installCounterTransport() {
  const calls = [];
  const pendingForB = [];
  const fetchTransport = vi.fn((url, options) => {
    const parsed = new URL(url, "http://localhost");
    const call = {
      owner: options.headers["X-Test-Owner"],
      path: parsed.pathname,
      params: Object.fromEntries(parsed.searchParams),
      signal: options.signal
    };
    calls.push(call);
    if (call.owner === "b") {
      const pending = deferred();
      pendingForB.push(pending);
      return pending.promise;
    }
    if (call.path.endsWith("/alerts")) {
      return Promise.resolve(jsonResponse({ content: [], page: 0, size: 1, totalElements: 9101, totalPages: 0 }));
    }
    if (call.path.includes("work-queue/summary")) {
      return Promise.resolve(jsonResponse({ totalFraudCases: 9202 }));
    }
    if (call.path.includes("suspicious-transactions/summary")) {
      return Promise.resolve(jsonResponse({ totalSuspiciousTransactions: 9303 }));
    }
    if (call.path.endsWith("/scored")) {
      return Promise.resolve(jsonResponse(listPage("a", call.params, 9404)));
    }
    if (call.path.includes("shadow-performance/summary/current")) {
      return Promise.resolve(jsonResponse({ message: "No current summary." }, 404));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
  vi.stubGlobal("fetch", fetchTransport);
  return { calls, pendingForB };
}

function installLifecycleTransport() {
  const calls = [];
  const pendingAList = deferred();
  const pendingADetail = deferred();
  const pendingBList = deferred();
  let aScoredPageReads = 0;
  let holdNextBList = false;
  const fetchTransport = vi.fn((url, options) => {
    const parsed = new URL(url, "http://localhost");
    const path = parsed.pathname;
    const owner = options.headers["X-Test-Owner"];
    const kind = path.endsWith("/feedback") ? "feedback"
      : path.endsWith("/scored") ? "list"
        : path.includes("transactions/scored/") ? "detail"
          : "summary";
    const call = {
      kind,
      owner,
      path,
      params: Object.fromEntries(parsed.searchParams),
      signal: options.signal
    };
    calls.push(call);

    if (kind === "feedback") return Promise.resolve(jsonResponse({}, 404));
    if (owner === "a" && kind === "list" && call.params.size === "25" && ++aScoredPageReads > 1) {
      return pendingAList.promise;
    }
    if (owner === "a" && kind === "detail") return pendingADetail.promise;
    if (owner === "b" && kind === "list" && call.params.size === "25" && holdNextBList) {
      holdNextBList = false;
      return pendingBList.promise;
    }
    if (owner === "b" && kind === "detail") {
      const transactionId = path.match(/\/transactions\/scored\/([^/]+)/)?.[1] || "txn-b";
      return Promise.resolve(jsonResponse(detailFor("b", transactionId)));
    }
    if (path.endsWith("/alerts")) {
      return Promise.resolve(jsonResponse({ content: [], page: 0, size: 1, totalElements: owner === "a" ? 9101 : 1101, totalPages: 0 }));
    }
    if (path.includes("work-queue/summary")) {
      return Promise.resolve(jsonResponse({ totalFraudCases: owner === "a" ? 9202 : 1202 }));
    }
    if (path.includes("suspicious-transactions/summary")) {
      return Promise.resolve(jsonResponse({ totalSuspiciousTransactions: owner === "a" ? 9303 : 1303 }));
    }
    if (path.endsWith("/scored")) {
      if (call.params.size === "1") {
        return Promise.resolve(jsonResponse(listPage(owner, call.params, owner === "a" ? 9404 : 1404)));
      }
      const transactionId = owner === "b"
        ? `txn-b-${call.params.query || "initial"}-${call.params.page || "0"}`
        : "txn-a";
      return Promise.resolve(jsonResponse(listPage(owner, call.params, owner === "a" ? 731 : 51, transactionId)));
    }
    if (path.includes("shadow-performance/summary/current")) {
      return Promise.resolve(jsonResponse({ message: "No current summary." }, 404));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
  vi.stubGlobal("fetch", fetchTransport);
  return {
    calls,
    pendingAList,
    pendingADetail,
    pendingBList,
    get holdNextBList() { return holdNextBList; },
    set holdNextBList(value) { holdNextBList = value; }
  };
}

function session(owner, capability = true) {
  return {
    userId: "same-analyst", issuer: owner, subject: "subject-" + owner, roles: ["READ_ONLY_ANALYST"],
    authorities: capability === true ? [AUTHORITIES.TRANSACTION_MONITOR_READ]
      : capability === false ? [AUTHORITIES.FRAUD_CASE_READ] : []
  };
}

function counterSession(owner, transactionCapability = true) {
  return {
    userId: "same-analyst",
    issuer: owner,
    subject: `subject-${owner}`,
    roles: [owner === "a" ? "ANALYST_A" : "ANALYST_B"],
    authorities: [
      AUTHORITIES.ALERT_READ,
      AUTHORITIES.FRAUD_CASE_READ,
      AUTHORITIES.SUSPICIOUS_TRANSACTION_READ,
      ...(transactionCapability ? [AUTHORITIES.TRANSACTION_MONITOR_READ] : []),
      AUTHORITIES.SHADOW_PERFORMANCE_READ
    ]
  };
}

function listPage(owner, params = {}, total = owner === "a" ? 731 : 2, transactionId = "txn-" + owner) {
  return {
    content: [{
      transactionId, customerId: "customer-" + owner,
      merchantInfo: { merchantName: "Merchant " + owner, merchantId: "merchant-" + owner, channel: "ECOMMERCE" },
      transactionAmount: { amount: 10, currency: "PLN" }, riskLevel: "HIGH", fraudScore: 0.8,
      alertRecommended: true, scoredAt: "2026-11-04T10:00:00Z"
    }],
    page: Number(params.page || 0), size: Number(params.size || 25), totalElements: total,
    totalPages: Math.ceil(total / Number(params.size || 25))
  };
}

function alertPage(owner, customerId = `customer-${owner}`) {
  return {
    content: [{
      alertId: `alert-${owner}`,
      alertReason: `private-reason-${owner}`,
      alertStatus: "OPEN",
      customerId,
      fraudScore: 0.8,
      riskLevel: "HIGH",
      transactionId: `transaction-${owner}`,
      alertTimestamp: "2026-04-20T09:00:10Z"
    }],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1
  };
}

function malformedAlertResponse(row) {
  const validRow = alertPage("malformed").content[0];
  const invalidRow = Array.isArray(row) ? row : { ...validRow, ...row };
  return jsonResponse({ ...alertPage("malformed"), content: [invalidRow] });
}

function privateApiFailure(status, error = "PRIVATE_BACKEND_CODE", details = [PRIVATE_ALERT_DIAGNOSTIC]) {
  return jsonResponse({ error, message: PRIVATE_ALERT_DIAGNOSTIC, details }, status);
}

function detailFor(owner, transactionId) {
  return availableDetail({ transactionId, merchantInfo: { merchantName: "Merchant " + owner, merchantId: "merchant-" + owner } });
}

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

function withAbort(promise, signal) {
  return new Promise((resolve, reject) => {
    signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
    promise.then(resolve, reject);
  });
}
