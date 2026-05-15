import { useEffect } from "react";
import { TransactionScoringWorkspaceContainer } from "./TransactionScoringWorkspaceContainer.jsx";
import { useScoredTransactionStream } from "./useScoredTransactionStream.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";

export function TransactionScoringWorkspaceRuntime({
  route,
  sharedWorkspaceReadsEnabled,
  setCounterValue,
  children
}) {
  const { canReadTransactions } = useWorkspaceRuntime();
  const transactionStreamState = useScoredTransactionStream({
    enabled: sharedWorkspaceReadsEnabled && canReadTransactions === true
  });

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

  function refreshWorkspace() {
    transactionStreamState.refresh();
  }

  return children({
    workspaceContent: (
      <TransactionScoringWorkspaceContainer
        headingLabel={route.heading.label}
        transactionStreamState={transactionStreamState}
        onRetryWorkspace={refreshWorkspace}
        onFiltersChange={changeTransactionFilters}
        onPageChange={changeTransactionPage}
        onPageSizeChange={changeTransactionPageSize}
      />
    ),
    navigationState: {
      transactionPage: transactionStreamState.page
    },
    error: transactionStreamState.error,
    refreshWorkspace
  });
}
