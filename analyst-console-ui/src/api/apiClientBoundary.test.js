import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

describe("api client boundary", () => {
  it("keeps default wrappers compatibility-only", () => {
    const source = readFileSync(join(process.cwd(), "src/api/alertsApi.js"), "utf8");

    expect(source).toContain("Compatibility-only default client. Auth-sensitive workspace code must use createAlertsApiClient({ session, authProvider }).");
  });

  it("runs the FDP-49 boundary script that blocks wrappers, re-exports, dynamic imports and raw fetch", () => {
    expect(() => execFileSync("node", ["../scripts/check-fdp49-api-client-boundary.mjs"], {
      cwd: process.cwd(),
      stdio: "pipe"
    })).not.toThrow();
  });
});
