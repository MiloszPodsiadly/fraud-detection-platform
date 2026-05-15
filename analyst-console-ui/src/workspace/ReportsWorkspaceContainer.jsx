import { ReportsWorkspacePage } from "../pages/ReportsWorkspacePage.jsx";

export function ReportsWorkspaceContainer(props) {
  return (
    <ReportsWorkspacePage
      {...props}
      workspaceHeadingProps={workspaceHeadingProps("Review visibility")}
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
