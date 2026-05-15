import { FraudTransactionWorkspacePage } from "../pages/FraudTransactionWorkspacePage.jsx";

export function FraudTransactionWorkspaceContainer(props) {
  return (
    <FraudTransactionWorkspacePage
      {...props}
      workspaceHeadingProps={workspaceHeadingProps("Alert review queue")}
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
