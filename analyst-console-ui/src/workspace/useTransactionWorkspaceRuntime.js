import { useEffect } from "react";
import { useAlertQueue } from "./useAlertQueue.js";
import { useScoredTransactionStream } from "./useScoredTransactionStream.js";

export function useTransactionWorkspaceRuntime({
  workspacePage,
  sharedWorkspaceReadsEnabled,
  canReadAlerts,
  canReadTransactions,
  setCounterValue
}) {
  const alertQueueState = useAlertQueue({
    enabled: workspacePage === "fraudTransaction" && sharedWorkspaceReadsEnabled && canReadAlerts === true
  });
  const transactionStreamState = useScoredTransactionStream({
    enabled: workspacePage === "transactionScoring" && sharedWorkspaceReadsEnabled && canReadTransactions === true
  });

  useEffect(() => {
    if (canReadAlerts === true) {
      setCounterValue("alerts", alertQueueState.page.totalElements);
    }
  }, [alertQueueState.page.totalElements, canReadAlerts, setCounterValue]);

  useEffect(() => {
    if (canReadTransactions === true) {
      setCounterValue("transactions", transactionStreamState.page.totalElements);
    }
  }, [canReadTransactions, setCounterValue, transactionStreamState.page.totalElements]);

  function changeTransactionPage(page) {
    transactionStreamState.setRequest((current) => ({ ...current, page: Math.min(Math.max(Number(page) || 0, 0), 1000) }));
  }

  function changeTransactionPageSize(size) {
    transactionStreamState.setRequest((current) => ({ ...current, page: 0, size: Math.min(Math.max(Number(size) || 25, 1), 100) }));
  }

  function changeTransactionFilters(filters) {
    transactionStreamState.setRequest((current) => ({
      ...current,
      ...filters,
      page: 0
    }));
  }

  function changeAlertPage(page) {
    alertQueueState.setRequest((current) => ({ ...current, page }));
  }

  function changeAlertPageSize(size) {
    alertQueueState.setRequest({ page: 0, size });
  }

  return {
    alertQueueState,
    transactionStreamState,
    changeTransactionFilters,
    changeTransactionPage,
    changeTransactionPageSize,
    changeAlertPage,
    changeAlertPageSize
  };
}
