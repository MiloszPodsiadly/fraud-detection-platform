import { describe, expect, it, vi } from "vitest";
import { createAlertReadOnlyBridgeApiClient } from "./alertReadOnlyBridgeApi.js";

describe("FDP-67 alert read-only bridge API client", () => {
  it("AlertReadOnlyClientUsesExistingAlertDetailEndpointTest", async () => {
    const apiClient = {
      getAlert: vi.fn().mockResolvedValue({ alertId: "alert-1" }),
      submitAnalystDecision: vi.fn()
    };
    const bridgeClient = createAlertReadOnlyBridgeApiClient(apiClient);
    const signal = new AbortController().signal;

    await bridgeClient.getAlert("alert-1", { signal });

    expect(apiClient.getAlert).toHaveBeenCalledWith("alert-1", { signal });
  });

  it("AlertReadOnlyClientHasNoMutationMethodsTest", () => {
    const bridgeClient = createAlertReadOnlyBridgeApiClient({
      getAlert: vi.fn(),
      submitAnalystDecision: vi.fn(),
      updateFraudCase: vi.fn()
    });

    expect(Object.keys(bridgeClient)).toEqual(["getAlert"]);
    expect(bridgeClient.submitAnalystDecision).toBeUndefined();
    expect(bridgeClient.updateFraudCase).toBeUndefined();
  });

  it("AlertReadOnlyClientDoesNotCallDecisionEndpointTest", async () => {
    const apiClient = {
      getAlert: vi.fn().mockResolvedValue({ alertId: "alert-1" }),
      submitAnalystDecision: vi.fn()
    };

    await createAlertReadOnlyBridgeApiClient(apiClient).getAlert("alert-1");

    expect(apiClient.submitAnalystDecision).not.toHaveBeenCalled();
  });

  it("AlertReadOnlyClientDoesNotCallAssistantSummaryEndpointTest", async () => {
    const apiClient = {
      getAlert: vi.fn().mockResolvedValue({ alertId: "alert-1" }),
      getAssistantSummary: vi.fn()
    };

    await createAlertReadOnlyBridgeApiClient(apiClient).getAlert("alert-1");

    expect(apiClient.getAssistantSummary).not.toHaveBeenCalled();
  });

  it("AlertReadOnlyClientDoesNotLogRawIdentifiersTest", async () => {
    const logSpy = vi.spyOn(console, "log").mockImplementation(() => {});
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const apiClient = {
      getAlert: vi.fn().mockResolvedValue({ alertId: "alert-secret" })
    };

    try {
      await createAlertReadOnlyBridgeApiClient(apiClient).getAlert("alert-secret");

      expect(logSpy).not.toHaveBeenCalled();
      expect(errorSpy).not.toHaveBeenCalled();
    } finally {
      logSpy.mockRestore();
      errorSpy.mockRestore();
    }
  });
});
