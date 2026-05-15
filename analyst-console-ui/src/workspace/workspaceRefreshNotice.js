export function noticeForWorkspaceRefreshResult(result) {
  if (!result || result.refreshed === true) {
    return null;
  }
  if (result.reason === "blocked-session") {
    return {
      tone: "warning",
      title: "Refresh skipped.",
      message: "Refresh was skipped because the current session is not active."
    };
  }
  if (result.reason === "refresh-failed") {
    return {
      tone: "warning",
      title: "Refresh could not start.",
      message: "Refresh could not be started. Try again."
    };
  }
  return null;
}
