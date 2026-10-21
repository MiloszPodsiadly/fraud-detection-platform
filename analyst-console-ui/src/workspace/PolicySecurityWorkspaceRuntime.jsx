import { PolicySecurityPage } from "../pages/PolicySecurityPage.jsx";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function PolicySecurityWorkspaceRuntime({ sharedWorkspaceReadsEnabled, children }) {
  const { apiClient, workspaceSessionResetKey, canReadPolicySecurity } = useWorkspaceRuntime();
  return children(createWorkspaceRuntimeResult({
    workspaceContent: <PolicySecurityPage key={workspaceSessionResetKey} apiClient={apiClient}
      enabled={sharedWorkspaceReadsEnabled && canReadPolicySecurity === true} />,
    refreshWorkspace: () => Promise.resolve()
  }));
}
