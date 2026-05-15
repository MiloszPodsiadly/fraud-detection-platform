import { TransactionScoringWorkspacePage } from "../pages/TransactionScoringWorkspacePage.jsx";

export function TransactionScoringWorkspaceContainer(props) {
  return (
    <TransactionScoringWorkspacePage
      {...props}
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
