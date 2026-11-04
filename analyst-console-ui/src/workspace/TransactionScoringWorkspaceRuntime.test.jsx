import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useLayoutEffect, useRef } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AUTHORITIES } from "../auth/session.js";
import { availableDetail } from "../transactions/transactionRiskIntelligenceFixtures.js";
import { WorkspaceRuntimeProvider } from "./WorkspaceRuntimeProvider.jsx";
import { WorkspaceDashboardShell } from "./WorkspaceDashboardShell.jsx";
import { WORKSPACE_ROUTE_REGISTRY } from "./WorkspaceRouteRegistry.jsx";

const provider = {
  kind: "demo",
  getRequestHeaders: (session) => ({ "X-Test-Owner": session.issuer })
};
const noOp = () => {};
const route = WORKSPACE_ROUTE_REGISTRY.transactionScoring;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("transaction scoring read boundary with real provider, API client, hooks and views", () => {
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
    fireEvent.change(screen.getByLabelText("Classification"), { target: { value: "SUSPICIOUS" } });
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
    installTransport({ list: (call) => call.owner === "a" ? jsonResponse(listPage("a", call.params)) : next.promise });
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
    await waitFor(() => expect(document.querySelector(".workspaceTabActive")).toHaveTextContent("731"));
    frames.length = 0;
    rerender(shell(session("b")));
    expect(frames.length).toBeGreaterThan(0);
    for (const text of frames) {
      expect(text).not.toContain("731");
      expect(text).not.toContain("Merchant a");
    }
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

function session(owner, capability = true) {
  return {
    userId: "same-analyst", issuer: owner, subject: "subject-" + owner, roles: ["READ_ONLY_ANALYST"],
    authorities: capability === true ? [AUTHORITIES.TRANSACTION_MONITOR_READ]
      : capability === false ? [AUTHORITIES.FRAUD_CASE_READ] : []
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
