import { GovernanceWorkspacePage } from "../pages/GovernanceWorkspacePage.jsx";

export function GovernanceWorkspaceContainer(props) {
  return (
    <GovernanceWorkspacePage
      {...props}
      workspaceHeadingProps={workspaceHeadingProps("Operator review queue")}
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
