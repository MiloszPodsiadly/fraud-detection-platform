import { describe, expect, it } from "vitest";
import { demoAuthHeaders, getInitialDemoSession } from "./demoSession.js";
import { normalizeSession } from "./session.js";

describe("demoSession", () => {
  it("maps the UI session contract to demo auth headers", () => {
    const session = normalizeSession({ userId: "analyst-1", roles: ["REVIEWER"] });

    expect(demoAuthHeaders(session)).toEqual({
      "X-Demo-User-Id": "analyst-1",
      "X-Demo-Roles": "REVIEWER"
    });
  });

  it("keeps unauthenticated sessions header-free", () => {
    const session = normalizeSession({ userId: "", roles: [] });

    expect(demoAuthHeaders(session)).toEqual({});
  });

  it("provides a normalized default demo session", () => {
    const session = getInitialDemoSession();

    expect(session.userId).toBeTruthy();
    expect(session.authorities).toContain("alert:read");
  });
});
