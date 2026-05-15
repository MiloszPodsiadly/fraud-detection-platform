const RESULT_KEYS = Object.freeze([
  "workspaceContent",
  "navigationState",
  "detailRouterState",
  "error",
  "refreshWorkspace"
]);

// Keep this object narrow so WorkspaceDashboardShell does not grow a meta-prop contract.
export function createWorkspaceRuntimeResult(input) {
  rejectUnknownKeys(input);
  const {
  workspaceContent,
  navigationState = {},
  detailRouterState = {},
  error = null,
  refreshWorkspace
  } = input;
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

function rejectUnknownKeys(input) {
  const extraKeys = Object.keys(input || {}).filter((key) => !RESULT_KEYS.includes(key));
  if (extraKeys.length > 0) {
    throw new Error(`Workspace runtime result contains unsupported keys: ${extraKeys.join(", ")}`);
  }
}

function isPlainObject(value) {
  return Boolean(value) && Object.getPrototypeOf(value) === Object.prototype;
}
