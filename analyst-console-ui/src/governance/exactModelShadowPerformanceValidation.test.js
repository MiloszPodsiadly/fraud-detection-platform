import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { isValidExactModelShadowPerformance } from "./exactModelShadowPerformanceValidation.js";

describe("exact Model Shadow Performance validation", () => {
  it("accepts canonical UTC timestamps with one through nine fractional digits", () => {
    const value = fixture();
    value.evaluationSummary.evaluationWindow.fromInclusive = "2026-06-01T00:00:00.1Z";
    value.evaluationSummary.evaluationWindow.toInclusive = "2026-06-09T23:59:59.123456789Z";

    expect(isValidExactModelShadowPerformance(value)).toBe(true);
  });

  it("accepts both Python-produced source verification levels", () => {
    expect(isValidExactModelShadowPerformance(fixture())).toBe(true);
    expect(isValidExactModelShadowPerformance(fixture("python-generated-claim-only-v1"))).toBe(true);
  });

  it("rejects permissive timestamps and semantic drift", () => {
    const cases = [
      (value) => { value.evaluationSummary.evaluationWindow.fromInclusive = "2026-06-01T02:00:00+02:00"; },
      (value) => { value.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.categoryCounts.BOTH_LOW = 2; },
      (value) => { value.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.unexpected = true; },
      (value) => { value.rawRecords = [{ transactionReference: "forbidden" }]; },
      (value) => { value.coverage.sourcePopulationStatus = "COMPLETE"; },
      (value) => { value.operationalDiagnostics.fallbackRate.value = 0; },
      (value) => { value.sourceEvaluation.sourceDatasetVerificationLevel = "UNKNOWN"; },
      (value) => { delete value.sourceEvaluation.sourceDatasetVerificationLevel; }
    ];

    for (const mutate of cases) {
      const value = fixture();
      mutate(value);
      expect(isValidExactModelShadowPerformance(value)).toBe(false);
    }
  });
});

function fixture(directory = "python-generated-v1") {
  return JSON.parse(readFileSync(resolve(
    "..", "alert-service", "src", "test", "resources", "fixtures",
    "exact-model-shadow-performance", directory, "exact_model_shadow_performance.json"
  ), "utf8"));
}
