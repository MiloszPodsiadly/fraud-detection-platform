import { TransactionScoringWorkspacePage } from "../pages/TransactionScoringWorkspacePage.jsx";

export function TransactionScoringWorkspaceContainer({
  headingLabel = "Transaction scoring stream",
  transactionStreamState,
  onRetryWorkspace,
  onFiltersChange,
  onPageChange,
  onPageSizeChange,
  apiClient,
  canReadTransactions
}) {
  return (
    <TransactionScoringWorkspacePage
      transactionPage={transactionStreamState.page}
      transactionPageRequest={transactionStreamState.request}
      isLoading={transactionStreamState.isLoading}
      error={transactionStreamState.error}
      onRetry={onRetryWorkspace}
      onTransactionFiltersChange={onFiltersChange}
      onTransactionPageChange={onPageChange}
      onTransactionPageSizeChange={onPageSizeChange}
      apiClient={apiClient}
      canReadTransactions={canReadTransactions}
      workspaceHeadingProps={workspaceHeadingProps(headingLabel)}
    />
  );
}

function workspaceHeadingProps(label) {
  return {
    tabIndex: -1,
    "data-workspace-heading": "",
    "data-workspace-label": label
  };
}
