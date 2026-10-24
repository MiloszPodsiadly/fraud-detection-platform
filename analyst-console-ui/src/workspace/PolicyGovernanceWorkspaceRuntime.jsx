import { PolicyGovernancePage } from "../pages/PolicyGovernancePage.jsx";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

export function PolicyGovernanceWorkspaceRuntime({ sharedWorkspaceReadsEnabled, children }) {
  const { apiClient, workspaceSessionResetKey, canReadPolicyGovernance } = useWorkspaceRuntime();
  const page = <PolicyGovernancePage
    key={workspaceSessionResetKey}
    apiClient={apiClient}
    enabled={sharedWorkspaceReadsEnabled && canReadPolicyGovernance === true}
  />;
  return children(createWorkspaceRuntimeResult({
    workspaceContent: page,
    refreshWorkspace: () => Promise.resolve()
  }));
}
