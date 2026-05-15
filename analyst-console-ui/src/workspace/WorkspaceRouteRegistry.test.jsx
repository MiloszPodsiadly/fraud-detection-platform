import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { WORKSPACE_ROUTE_ENTRIES, WORKSPACE_ROUTE_REGISTRY, resolveWorkspaceRoute } from "./WorkspaceRouteRegistry.jsx";

const registrySource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "WorkspaceRouteRegistry.jsx"), "utf8");

describe("WorkspaceRouteRegistry", () => {
  it("contains every workspace route with declarative metadata", () => {
    expect(WORKSPACE_ROUTE_ENTRIES.map((route) => route.key)).toEqual([
      "transactionScoring",
      "fraudTransaction",
      "analyst",
      "reports",
      "compliance"
    ]);

    for (const route of WORKSPACE_ROUTE_ENTRIES) {
      expect(route).toEqual(expect.objectContaining({
        key: expect.any(String),
        label: expect.any(String),
        navigationLabel: expect.any(String),
        routeValue: expect.any(String),
        href: expect.any(String),
        capabilityKey: expect.any(String),
        Runtime: expect.any(Function)
      }));
      expect(route.heading).toEqual(expect.objectContaining({ label: expect.any(String) }));
    }
  });

  it("resolves unknown workspace keys to the analyst default", () => {
    expect(resolveWorkspaceRoute("unknown")).toBe(WORKSPACE_ROUTE_REGISTRY.analyst);
  });

  it("does not become an API or authorization engine", () => {
    expect(registrySource).not.toMatch(/apiClient|authProvider|authorit/i);
    expect(registrySource).not.toMatch(/\bfetch\s*\(/);
    expect(registrySource).not.toMatch(/from\s+["'][^"']*\/api\//);
    expect(registrySource).not.toMatch(/\/(?:api|governance|system|bff)\//);
  });
});
