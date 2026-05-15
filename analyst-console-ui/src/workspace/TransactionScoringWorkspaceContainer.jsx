import { TransactionScoringWorkspacePage } from "../pages/TransactionScoringWorkspacePage.jsx";

export function TransactionScoringWorkspaceContainer({
  transactionStreamState,
  onRetryWorkspace,
  onFiltersChange,
  onPageChange,
  onPageSizeChange
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
      workspaceHeadingProps={workspaceHeadingProps("Transaction scoring stream")}
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
