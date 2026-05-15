import { describe, expect, it, vi } from "vitest";
import { createWorkspaceRuntimeResult } from "./workspaceRuntimeResult.js";

describe("createWorkspaceRuntimeResult", () => {
  it("normalizes optional state and preserves the refresh contract", () => {
    const refreshWorkspace = vi.fn();
    const result = createWorkspaceRuntimeResult({
      workspaceContent: "content",
      refreshWorkspace
    });

    expect(result).toEqual({
      workspaceContent: "content",
      navigationState: {},
      detailRouterState: {},
      error: null,
      refreshWorkspace
    });
  });

  it("fails fast when required runtime contract fields are missing", () => {
    expect(() => createWorkspaceRuntimeResult({ refreshWorkspace: vi.fn() }))
      .toThrow("workspaceContent");
    expect(() => createWorkspaceRuntimeResult({ workspaceContent: "content" }))
      .toThrow("refreshWorkspace");
    expect(() => createWorkspaceRuntimeResult({
      workspaceContent: "content",
      refreshWorkspace: vi.fn(),
      navigationState: null
    })).toThrow("navigationState");
  });
});
