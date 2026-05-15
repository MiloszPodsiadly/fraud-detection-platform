import { useCallback, useEffect, useState } from "react";
import { WORKSPACE_ROUTE_REGISTRY, getWorkspaceRoute } from "./WorkspaceRouteRegistry.jsx";

export const WORKSPACE_PAGES = Object.fromEntries(
  Object.entries(WORKSPACE_ROUTE_REGISTRY).map(([key, route]) => [key, { label: route.label, path: route.routeValue }])
);

export function useWorkspaceRoute() {
  const [route, setRoute] = useState(readWorkspaceRoute);

  useEffect(() => {
    const handlePopState = () => setRoute(readWorkspaceRoute());
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const navigateWorkspace = useCallback((page) => {
    const nextPage = getWorkspaceRoute(page)?.key || "analyst";
    const params = new URLSearchParams(window.location.search);
    params.delete("alertId");
    params.delete("fraudCaseId");
    if (nextPage === "analyst") {
      params.delete("workspace");
    } else {
      params.set("workspace", getWorkspaceRoute(nextPage).routeValue);
    }
    pushRoute(params);
    setRoute(readWorkspaceRoute());
  }, []);

  const openAlert = useCallback((alertId) => {
    const params = new URLSearchParams(window.location.search);
    params.delete("fraudCaseId");
    params.set("alertId", alertId);
    pushRoute(params);
    setRoute(readWorkspaceRoute());
  }, []);

  const openFraudCase = useCallback((caseId) => {
    const params = new URLSearchParams(window.location.search);
    params.delete("alertId");
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
  const matchedWorkspace = Object.entries(WORKSPACE_PAGES)
    .find(([, page]) => page.path === workspace)?.[0] || null;
  return {
    workspacePage: matchedWorkspace || "analyst",
    invalidWorkspaceRoute: workspace && !matchedWorkspace ? workspace : null,
    selectedAlertId: params.get("alertId"),
    selectedFraudCaseId: params.get("fraudCaseId")
  };
}

export function workspaceHref(page) {
  const route = getWorkspaceRoute(page) || getWorkspaceRoute("analyst");
  return route.key === "analyst" ? "/" : `/?workspace=${route.routeValue}`;
}

function pushRoute(params) {
  const query = params.toString();
  window.history.pushState({}, "", `${window.location.pathname}${query ? `?${query}` : ""}`);
}
