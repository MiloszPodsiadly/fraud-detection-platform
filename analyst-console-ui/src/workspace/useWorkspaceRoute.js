import { useCallback, useEffect, useState } from "react";

export const WORKSPACE_PAGES = {
  analyst: { label: "Fraud Case", path: "analyst" },
  fraudTransaction: { label: "Fraud Transaction", path: "fraud-transaction" },
  transactionScoring: { label: "Transaction Scoring", path: "transaction-scoring" },
  compliance: { label: "Compliance", path: "compliance" },
  reports: { label: "Reports", path: "reports" }
};

export function useWorkspaceRoute() {
  const [route, setRoute] = useState(readWorkspaceRoute);

  useEffect(() => {
    const handlePopState = () => setRoute(readWorkspaceRoute());
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const navigateWorkspace = useCallback((page) => {
    const nextPage = WORKSPACE_PAGES[page] ? page : "analyst";
    const params = new URLSearchParams(window.location.search);
    params.delete("alertId");
    params.delete("fraudCaseId");
    if (nextPage === "analyst") {
      params.delete("workspace");
    } else {
      params.set("workspace", WORKSPACE_PAGES[nextPage].path);
    }
    pushRoute(params);
    setRoute(readWorkspaceRoute());
  }, []);

  const openAlert = useCallback((alertId) => {
    const params = new URLSearchParams();
    params.set("alertId", alertId);
    pushRoute(params);
    setRoute(readWorkspaceRoute());
  }, []);

  const openFraudCase = useCallback((caseId) => {
    const params = new URLSearchParams();
    params.set("fraudCaseId", caseId);
    pushRoute(params);
    setRoute(readWorkspaceRoute());
  }, []);

  const clearSelection = useCallback(() => {
    const params = new URLSearchParams(window.location.search);
    params.delete("alertId");
    params.delete("fraudCaseId");
    pushRoute(params);
    setRoute(readWorkspaceRoute());
  }, []);

  return {
    ...route,
    navigateWorkspace,
    openAlert,
    openFraudCase,
    clearSelection,
    workspaceHref
  };
}

export function readWorkspaceRoute() {
  const params = new URLSearchParams(window.location.search);
  const workspace = params.get("workspace");
  return {
    workspacePage: Object.entries(WORKSPACE_PAGES)
      .find(([, page]) => page.path === workspace)?.[0] || "analyst",
    selectedAlertId: params.get("alertId"),
    selectedFraudCaseId: params.get("fraudCaseId")
  };
}

export function workspaceHref(page) {
  return page === "analyst" ? "/" : `/?workspace=${WORKSPACE_PAGES[page]?.path || WORKSPACE_PAGES.analyst.path}`;
}

function pushRoute(params) {
  const query = params.toString();
  window.history.pushState({}, "", `${window.location.pathname}${query ? `?${query}` : ""}`);
}
