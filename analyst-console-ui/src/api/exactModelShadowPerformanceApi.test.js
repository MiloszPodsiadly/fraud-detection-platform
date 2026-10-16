import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createDemoAuthProvider } from "../auth/authProvider.js";
import { createAlertsApiClient } from "./alertsApi.js";

describe("exact model Shadow Performance API client", () => {
  afterEach(() => vi.restoreAllMocks());

  it("sends the complete exact selector as a read-only request", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(fixture()));
    const signal = new AbortController().signal;

    const result = await client().getExactModelShadowPerformance(selector(), { signal });

    expect(result.schemaVersion).toBe("exact-model-shadow-performance-v1");
    const [url, options] = fetchMock.mock.calls[0];
    const parsed = new URL(url, "http://localhost");
    expect(parsed.pathname).toBe("/api/v1/governance/model-shadow-performance");
    expect(Object.fromEntries(parsed.searchParams)).toEqual(selector());
    expect(options.signal).toBe(signal);
    expect(options).not.toHaveProperty("method");
    expect(options).not.toHaveProperty("body");
  });

  it("rejects incomplete selectors before network access", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    await expect(client().getExactModelShadowPerformance({ ...selector(), modelVersion: "" }))
      .rejects.toBeInstanceOf(TypeError);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("returns a safe state for unsupported contracts", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ ...fixture(), populationType: "SCORING_OCCURRENCES" }));
    await expect(client().getExactModelShadowPerformance(selector()))
      .resolves.toEqual({ state: "invalid-response" });
  });
});

function selector() {
  return {
    modelName: "python-logistic-fraud-model",
    modelVersion: "2026-10-15.shadow",
    featureContractVersion: "feature-contract-v2",
    modelArtifactSha256: "a".repeat(64),
    fromInclusive: "2026-06-01T00:00:00Z",
    toInclusive: "2026-06-09T23:59:59Z"
  };
}

function fixture() {
  return JSON.parse(readFileSync(resolve(
    "..", "alert-service", "src", "test", "resources", "fixtures",
    "exact-model-shadow-performance", "python-generated-v1", "exact_model_shadow_performance.json"
  ), "utf8"));
}

function client() {
  return createAlertsApiClient({
    session: {
      userId: "admin-1",
      roles: [],
      extraAuthorities: ["model-shadow-performance:read"],
      authorities: ["model-shadow-performance:read"]
    },
    authProvider: createDemoAuthProvider()
  });
}

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
