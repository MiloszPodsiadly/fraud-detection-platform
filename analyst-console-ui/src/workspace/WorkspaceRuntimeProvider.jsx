import { useMemo } from "react";
import { createAlertsApiClient } from "../api/alertsApi.js";
import { AUTHORITIES, hasAuthority } from "../auth/session.js";
import { WorkspaceRuntimeContext } from "./useWorkspaceRuntime.js";

export function WorkspaceRuntimeProvider({ session, authProvider, enabled = true, children }) {
  const authenticated = Boolean(session?.userId);
  const apiClient = useMemo(
    () => (enabled && authenticated ? createAlertsApiClient({ session, authProvider }) : null),
    [authProvider, authenticated, enabled, session]
  );
  const workspaceSessionResetKey = useMemo(
    () => workspaceSessionResetKeyFor(session, authProvider),
    [authProvider, session]
  );
  const value = useMemo(() => ({
    session,
    authProvider,
    apiClient,
    workspaceSessionResetKey,
    canReadAlerts: authorityState(session, AUTHORITIES.ALERT_READ),
    canReadFraudCases: authorityState(session, AUTHORITIES.FRAUD_CASE_READ),
    canReadTransactions: authorityState(session, AUTHORITIES.TRANSACTION_MONITOR_READ),
    canReadGovernance: authorityState(session, AUTHORITIES.TRANSACTION_MONITOR_READ),
    runtimeStatus: enabled && authenticated ? "ready" : "disabled"
  }), [apiClient, authProvider, enabled, authenticated, session, workspaceSessionResetKey]);

  return (
    <WorkspaceRuntimeContext.Provider value={value}>
      {children}
    </WorkspaceRuntimeContext.Provider>
  );
}

function authorityState(session, authority) {
  if (!session?.userId) {
    return undefined;
  }
  if (!Array.isArray(session.authorities) || session.authorities.length === 0) {
    return undefined;
  }
  return hasAuthority(session, authority);
}

function workspaceSessionResetKeyFor(session, authProvider) {
  const roles = Array.isArray(session?.roles) ? session.roles.join(",") : "";
  const authorities = Array.isArray(session?.authorities) ? session.authorities.join(",") : "";
  return [
    authProvider?.kind || "none",
    session?.userId || "",
    roles,
    authorities
  ].join(":");
}
