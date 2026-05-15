export function createWorkspaceRuntimeResult({
  workspaceContent,
  navigationState = {},
  detailRouterState = {},
  error = null,
  refreshWorkspace
}) {
  if (!workspaceContent) {
    throw new Error("Workspace runtime result requires workspaceContent.");
  }
  if (typeof refreshWorkspace !== "function") {
    throw new Error("Workspace runtime result requires refreshWorkspace function.");
  }
  if (!isPlainObject(navigationState)) {
    throw new Error("Workspace runtime result navigationState must be a plain object.");
  }
  if (!isPlainObject(detailRouterState)) {
    throw new Error("Workspace runtime result detailRouterState must be a plain object.");
  }

  return {
    workspaceContent,
    navigationState,
    detailRouterState,
    error,
    refreshWorkspace
  };
}

function isPlainObject(value) {
  return Boolean(value) && Object.getPrototypeOf(value) === Object.prototype;
}
