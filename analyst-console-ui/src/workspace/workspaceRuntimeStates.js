export const WORKSPACE_DETAIL_RUNTIME_STATE = Object.freeze({
  AVAILABLE: "available",
  NOT_MOUNTED: "not-mounted"
});

export function normalizeWorkspaceDetailRuntimeState(state) {
  return Object.values(WORKSPACE_DETAIL_RUNTIME_STATE).includes(state)
    ? state
    : WORKSPACE_DETAIL_RUNTIME_STATE.NOT_MOUNTED;
}

export function isWorkspaceDetailRuntimeAvailable(state) {
  return normalizeWorkspaceDetailRuntimeState(state) === WORKSPACE_DETAIL_RUNTIME_STATE.AVAILABLE;
}
