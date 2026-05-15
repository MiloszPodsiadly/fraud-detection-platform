import { useCallback, useEffect, useState } from "react";
import { noticeForWorkspaceRefreshResult } from "./workspaceRefreshNotice.js";

export function useWorkspaceRefreshNotice(workspaceKey) {
  const [refreshNotice, setRefreshNotice] = useState(null);

  useEffect(() => {
    setRefreshNotice(null);
  }, [workspaceKey]);

  const consumeRefreshResult = useCallback((result) => {
    const nextNotice = noticeForWorkspaceRefreshResult(result);
    setRefreshNotice(nextNotice);
    return result;
  }, []);

  return {
    refreshNotice,
    consumeRefreshResult
  };
}
