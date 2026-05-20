import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { readWorkspaceRoute, useWorkspaceRoute } from "./useWorkspaceRoute.js";

describe("useWorkspaceRoute", () => {
  it("parses initial workspace and detail ids from URL", () => {
    window.history.replaceState({}, "", "/?workspace=transaction-scoring&alertId=alert-1");

    expect(readWorkspaceRoute()).toEqual({
      workspacePage: "transactionScoring",
      invalidWorkspaceRoute: null,
      selectedAlertId: "alert-1",
      selectedFraudCaseId: null,
      selectedSuspiciousTransactionId: null
    });
  });

  it("falls back to analyst for unknown workspace", () => {
    window.history.replaceState({}, "", "/?workspace=unknown");

    expect(readWorkspaceRoute().workspacePage).toBe("analyst");
    expect(readWorkspaceRoute().invalidWorkspaceRoute).toBe("unknown");
  });

  it("navigates workspaces and clears stale detail selections", () => {
    window.history.replaceState({}, "", "/?alertId=alert-1");
    const { result } = renderHook(() => useWorkspaceRoute());

    act(() => result.current.navigateWorkspace("transactionScoring"));

    expect(result.current.workspacePage).toBe("transactionScoring");
    expect(result.current.invalidWorkspaceRoute).toBeNull();
    expect(result.current.selectedAlertId).toBeNull();
    expect(result.current.selectedSuspiciousTransactionId).toBeNull();
    expect(window.location.search).toBe("?workspace=transaction-scoring");
  });

  it("opens suspicious transaction detail on the dedicated workspace route", () => {
    window.history.replaceState({}, "", "/?workspace=transaction-scoring");
    const { result } = renderHook(() => useWorkspaceRoute());

    act(() => result.current.openSuspiciousTransaction("suspicious-1"));

    expect(result.current.workspacePage).toBe("suspiciousTransactions");
    expect(result.current.selectedSuspiciousTransactionId).toBe("suspicious-1");
    expect(window.location.search).toBe("?workspace=suspicious-transactions&suspiciousTransactionId=suspicious-1");
  });

  it("clickingViewAlertContextPreservesSuspiciousTransactionIdInRoute", () => {
    window.history.replaceState({}, "", "/?workspace=suspicious-transactions&suspiciousTransactionId=suspicious-1");
    const { result } = renderHook(() => useWorkspaceRoute());

    act(() => result.current.openAlert({ alertId: "alert-1", suspiciousTransactionId: "suspicious-1" }));

    expect(result.current.workspacePage).toBe("suspiciousTransactions");
    expect(result.current.selectedSuspiciousTransactionId).toBe("suspicious-1");
    expect(result.current.selectedAlertId).toBe("alert-1");
    expect(window.location.search).toBe("?workspace=suspicious-transactions&suspiciousTransactionId=suspicious-1&alertId=alert-1");
  });

  it("opens fraud case detail and responds to popstate", () => {
    window.history.replaceState({}, "", "/");
    const { result } = renderHook(() => useWorkspaceRoute());

    act(() => result.current.openFraudCase("case-1"));
    expect(result.current.selectedFraudCaseId).toBe("case-1");

    act(() => {
      window.history.pushState({}, "", "/?workspace=compliance");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });

    expect(result.current.workspacePage).toBe("compliance");
    expect(result.current.selectedFraudCaseId).toBeNull();
  });
});
