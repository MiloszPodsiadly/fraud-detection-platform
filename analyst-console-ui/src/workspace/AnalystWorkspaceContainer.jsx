import { AnalystWorkspacePage } from "../pages/AnalystWorkspacePage.jsx";

export function AnalystWorkspaceContainer(props) {
  return (
    <AnalystWorkspacePage
      {...props}
      workspaceHeadingProps={workspaceHeadingProps("Fraud Case Work Queue")}
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
