import { describe, expect, it } from "vitest";
import { validateTransactionRiskIntelligenceDetail } from "./transactionRiskIntelligenceValidation.js";

describe("transactionRiskIntelligenceValidation", () => {
  it("accepts valid AVAILABLE detail", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail()).valid).toBe(true);
  });

  it("accepts ABSENT with explicit null fields and empty arrays", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        status: "ABSENT",
        contractVersion: null,
        generatedAt: null,
        comparison: null,
        engines: [],
        diagnosticSignals: [],
        warnings: []
      }
    })).valid).toBe(true);
  });

  it("accepts UNAVAILABLE with explicit null fields and empty arrays", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        status: "UNAVAILABLE",
        contractVersion: null,
        generatedAt: null,
        comparison: null,
        engines: [],
        diagnosticSignals: [],
        warnings: []
      }
    })).valid).toBe(true);
  });

  it("accepts DEGRADED with warnings and comparison null", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        status: "DEGRADED",
        comparison: null,
        warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1 }]
      }
    })).valid).toBe(true);
  });

  it("rejects missing engineIntelligence", () => {
    const value = detail();
    delete value.engineIntelligence;

    expect(validateTransactionRiskIntelligenceDetail(value)).toMatchObject({ valid: false, reason: "MISSING_ENGINE_INTELLIGENCE" });
  });

  it("rejects invalid status", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), status: "EXECUTE" }
    })).valid).toBe(false);
  });

  it("rejects arrays above display bounds", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), engines: Array.from({ length: 3 }, (_, index) => engine({ engineId: `engine-${index}` })) }
    }))).toMatchObject({ valid: false, reason: "ENGINE_LIMIT_EXCEEDED" });
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), diagnosticSignals: Array.from({ length: 6 }, (_, index) => signal({ reasonCode: `R${index}` })) }
    }))).toMatchObject({ valid: false, reason: "DIAGNOSTIC_SIGNAL_LIMIT_EXCEEDED" });
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), warnings: Array.from({ length: 11 }, (_, index) => ({ warningCode: `WARNING_${index}`, count: index })) }
    }))).toMatchObject({ valid: false, reason: "WARNING_LIMIT_EXCEEDED" });
  });

  it("rejects reasonCodes above display bound", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        engines: [engine({ reasonCodes: ["A", "B", "C", "D", "E", "F"] })]
      }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_ENGINE" });
  });

  it.each([
    ["empty object", {}],
    ["unknown field", { banana: "x" }],
    ["missing agreementStatus", { riskMismatchStatus: "NOT_COMPARABLE", scoreDeltaBucket: "UNAVAILABLE" }],
    ["missing riskMismatchStatus", { agreementStatus: "PARTIAL", scoreDeltaBucket: "UNAVAILABLE" }],
    ["missing scoreDeltaBucket", { agreementStatus: "PARTIAL", riskMismatchStatus: "NOT_COMPARABLE" }],
    ["invalid enum", { agreementStatus: "BANANA", riskMismatchStatus: "NOT_COMPARABLE", scoreDeltaBucket: "UNAVAILABLE" }]
  ])("rejects comparison %s", (_caseName, comparison) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), comparison }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_COMPARISON" });
  });

  it.each([
    ["without engineId", { engineId: "" }],
    ["without engineType", { engineType: undefined }],
    ["with invalid status", { status: "FALLBACK_USED" }],
    ["with reasonCodes not array", { reasonCodes: "HIGH_VELOCITY" }],
    ["with non-string reasonCode", { reasonCodes: ["HIGH_VELOCITY", 1] }],
    ["with invalid scoreBucket", { scoreBucket: "NOT_COMPARABLE" }]
  ])("rejects engine %s", (_caseName, engineOverride) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), engines: [engine(engineOverride)] }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_ENGINE" });
  });

  it("accepts engine null risk level for unavailable projected risk", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), engines: [engine({ riskLevel: null, scoreBucket: "UNAVAILABLE" })] }
    })).valid).toBe(true);
  });

  it.each([
    ["without reasonCode", { reasonCode: "" }],
    ["without engineId", { engineId: "" }],
    ["without engineType", { engineType: undefined }],
    ["without engineStatus", { engineStatus: undefined }],
    ["without signalCategory", { signalCategory: "" }],
    ["with invalid engineStatus", { engineStatus: "SKIPPED" }],
    ["with invalid scoreBucket", { scoreBucket: "NOT_COMPARABLE" }]
  ])("rejects diagnostic signal %s", (_caseName, signalOverride) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), diagnosticSignals: [signal(signalOverride)] }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNAL" });
  });

  it("accepts diagnostic signal null risk level for operational diagnostics", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        diagnosticSignals: [signal({
          signalCategory: "OPERATIONAL_SIGNAL",
          riskLevel: null,
          scoreBucket: "UNAVAILABLE"
        })]
      }
    })).valid).toBe(true);
  });

  it.each([
    ["without warningCode", { warningCode: "" }],
    ["without count", { count: undefined }],
    ["with negative count", { count: -1 }],
    ["with non-numeric count", { count: "1" }],
    ["with non-integer count", { count: 1.5 }]
  ])("rejects warning %s", (_caseName, warningOverride) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1, ...warningOverride }]
      }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_WARNING" });
  });

  it.each([
    "rawMlRequest",
    "rawMLRequest",
    "RawMlRequest",
    "rawFeatureVector",
    "RAWFEATUREVECTOR",
    "finalDecision",
    "FinalDecision",
    "paymentAuthorization",
    "PaymentAuthorization"
  ])("rejects unsafe internal field %s case-insensitively", (unsafeFieldName) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        engines: [engine({ extra: { [unsafeFieldName]: "hidden" } })]
      }
    }))).toMatchObject({ valid: false, reason: "UNSAFE_DETAIL_RESPONSE" });
  });

  it("does not compute comparison values", () => {
    const comparison = {
      agreementStatus: "DISAGREEMENT",
      riskMismatchStatus: "MATERIAL_RISK_MISMATCH",
      scoreDeltaBucket: "LARGE"
    };
    const result = validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), comparison }
    }));

    expect(result.valid).toBe(true);
    expect(result.detail.engineIntelligence.comparison).toBe(comparison);
  });
});

function detail(overrides = {}) {
  return {
    transactionId: "txn-1",
    correlationId: "corr-1",
    transactionTimestamp: "2026-06-18T10:00:00Z",
    scoredAt: "2026-06-18T10:00:01Z",
    fraudScore: 0.91,
    riskLevel: "CRITICAL",
    alertRecommended: true,
    reasonCodes: ["HIGH_VELOCITY"],
    engineIntelligence: engineIntelligence(),
    ...overrides
  };
}

function engineIntelligence() {
  return {
    status: "AVAILABLE",
    contractVersion: 1,
    generatedAt: "2026-06-18T10:00:02Z",
    comparison: {
      agreementStatus: "PARTIAL",
      riskMismatchStatus: "NOT_COMPARABLE",
      scoreDeltaBucket: "UNAVAILABLE"
    },
    engines: [engine()],
    diagnosticSignals: [signal()],
    warnings: []
  };
}

function engine(overrides = {}) {
  return {
    engineId: "rules.primary",
    engineType: "RULES",
    status: "AVAILABLE",
    riskLevel: "CRITICAL",
    scoreBucket: "HIGH",
    reasonCodes: ["HIGH_VELOCITY"],
    ...overrides
  };
}

function signal(overrides = {}) {
  return {
    engineId: "rules.primary",
    engineType: "RULES",
    engineStatus: "AVAILABLE",
    signalCategory: "FRAUD_SIGNAL",
    riskLevel: "CRITICAL",
    scoreBucket: "HIGH",
    reasonCode: "HIGH_VELOCITY",
    ...overrides
  };
}
