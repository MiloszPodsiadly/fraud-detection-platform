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
    }))).toMatchObject({ valid: false, reason: "ENGINE_REASON_CODE_LIMIT_EXCEEDED" });
  });

  it("rejects unsafe internal fields anywhere in the display payload", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        engines: [engine({ extra: { [["raw", "Payload"].join("")]: "hidden" } })]
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
