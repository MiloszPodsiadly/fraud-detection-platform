import { afterEach, describe, expect, it, vi } from "vitest";
import { createDemoAuthProvider } from "../auth/authProvider.js";
import { modelCard } from "../test-utils/modelCardFixtures.js";
import { createAlertsApiClient } from "./alertsApi.js";

describe("Model Card API client", () => {
  afterEach(() => vi.restoreAllMocks());

  it("calls only the current read endpoint and carries abort state", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(modelCard()));
    const signal = new AbortController().signal;

    const result = await client().getCurrentModelCard({
      signal,
      method: "POST",
      body: JSON.stringify({ modelVersion: "secret" })
    });

    expect(result.cardType).toBe("ML_MODEL_CARD_V1");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/governance/model-card/current",
      expect.objectContaining({ signal })
    );
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("method");
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("body");
  });

  it("returns a safe state for unsupported contracts", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(modelCard({ promotionState: "APPROVED" })));
    await expect(client().getCurrentModelCard()).resolves.toEqual({ state: "invalid-response" });
  });

  it.each([401, 403, 404, 503])("propagates HTTP %s", async (status) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ message: "read failed" }, status));
    await expect(client().getCurrentModelCard()).rejects.toMatchObject({ status });
  });
});

function client() {
  return createAlertsApiClient({
    session: {
      userId: "admin-1",
      roles: ["FRAUD_OPS_ADMIN"],
      extraAuthorities: [],
      authorities: ["model-card:read"]
    },
    authProvider: createDemoAuthProvider()
  });
}

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}
