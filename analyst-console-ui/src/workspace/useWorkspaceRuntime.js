import { createContext, useContext } from "react";

export const WorkspaceRuntimeContext = createContext(null);

export function useWorkspaceRuntime() {
  const runtime = useContext(WorkspaceRuntimeContext);
  if (!runtime) {
    throw new Error("useWorkspaceRuntime must be used within WorkspaceRuntimeProvider");
  }
  return runtime;
}

export function useOptionalWorkspaceRuntime() {
  return useContext(WorkspaceRuntimeContext);
}
