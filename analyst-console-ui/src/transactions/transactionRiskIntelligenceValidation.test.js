import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { validateTransactionRiskIntelligenceDetail } from "./transactionRiskIntelligenceValidation.js";
import {
  absentRecommendationDetail,
  degradedRecommendationDetail,
  insufficientDataRecommendationDetail,
  malformedInvalidEngine,
  malformedInvalidWarning,
  malformedMissingEngineIntelligence,
  malformedRecommendationAvailableWithoutReason,
  malformedRecommendationAvailableWithoutGeneratedAt,
  malformedRecommendationAvailableWithoutSource,
  malformedRecommendationAvailableWithoutValue,
  malformedRecommendationAbsentWithInvalidGeneratedAt,
  malformedRecommendationBlankVersion,
  malformedRecommendationDegradedWithoutGeneratedAt,
  malformedRecommendationDegradedWithoutSource,
  malformedRecommendationFalseNonDecisioningFlag,
  malformedRecommendationMissingGeneratedAt,
  malformedRecommendationMissingFlags,
  malformedRecommendationMissingVersion,
  malformedRecommendationNullSource,
  malformedRecommendationTooManyReasonCodes,
  malformedRecommendationTooManyWarnings,
  malformedRecommendationUnavailableWithInvalidGeneratedAt,
  malformedRecommendationUnavailableWithValue,
  notApplicableRecommendationDetail,
  recommendCaseCreationDetail,
  recommendMonitorDetail,
  recommendNoActionDetail,
  recommendReviewDetail,
  recommendStepUpReviewDetail,
  unavailableRecommendationDetail
} from "./transactionRiskIntelligenceFixtures.js";

describe("transactionRiskIntelligenceValidation", () => {
  it("accepts valid AVAILABLE detail", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail()).valid).toBe(true);
  });

  it("accepts shared three-engine golden fixture", () => {
    const result = validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        status: "AVAILABLE",
        ...goldenEngineIntelligence()
      }
    }));

    expect(result.valid).toBe(true);
    expect(result.detail.engineIntelligence.engines.map((engine) => engine.engineId)).toEqual([
      "rules.primary",
      "ml.python.primary",
      "velocity.primary"
    ]);
  });

  it.each([
    ["review", recommendReviewDetail()],
    ["case creation", recommendCaseCreationDetail()],
    ["step up review", recommendStepUpReviewDetail()],
    ["monitor", recommendMonitorDetail()],
    ["no action", recommendNoActionDetail()],
    ["absent", absentRecommendationDetail()],
    ["not applicable", notApplicableRecommendationDetail()],
    ["insufficient data", insufficientDataRecommendationDetail()],
    ["unavailable", unavailableRecommendationDetail()],
    ["degraded", degradedRecommendationDetail()]
  ])("accepts public analyst recommendation fixture %s", (_caseName, fixture) => {
    expect(validateTransactionRiskIntelligenceDetail(fixture).valid).toBe(true);
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

  it("accepts DEGRADED with warnings and complete projected payload", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        status: "DEGRADED",
        warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1 }]
      }
    })).valid).toBe(true);
  });

  it("rejects missing engineIntelligence", () => {
    const value = detail();
    delete value.engineIntelligence;

    expect(validateTransactionRiskIntelligenceDetail(value)).toMatchObject({ valid: false, reason: "MISSING_ENGINE_INTELLIGENCE" });
  });

  it.each([
    ["top-level detail", () => detail({ extra: "x" }), "INVALID_DETAIL_RESPONSE"],
    ["engine-intelligence", () => detail({ engineIntelligence: { ...engineIntelligence(), extra: "x" } }), "INVALID_ENGINE_INTELLIGENCE_ENVELOPE"],
    ["analyst-recommendation", () => detail({ analystRecommendation: { ...analystRecommendation(), extra: "x" } }), "INVALID_ANALYST_RECOMMENDATION_ENVELOPE"],
    ["non-decisioning", () => detail({
      analystRecommendation: {
        ...analystRecommendation(),
        nonDecisioning: { ...analystRecommendation().nonDecisioning, extra: true }
      }
    }), "INVALID_ANALYST_RECOMMENDATION_NON_DECISIONING"]
  ])("rejects benign extra %s field", (_caseName, factory, reason) => {
    expect(validateTransactionRiskIntelligenceDetail(factory())).toMatchObject({ valid: false, reason });
  });

  it("rejects malformed display fixtures", () => {
    expect(validateTransactionRiskIntelligenceDetail(malformedMissingEngineIntelligence())).toMatchObject({
      valid: false,
      reason: "MISSING_ENGINE_INTELLIGENCE"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedInvalidEngine())).toMatchObject({
      valid: false,
      reason: "INVALID_ENGINE_INTELLIGENCE_ENGINE"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedInvalidWarning())).toMatchObject({
      valid: false,
      reason: "INVALID_ENGINE_INTELLIGENCE_WARNING"
    });
  });

  it("rejects missing analystRecommendation", () => {
    const value = detail();
    delete value.analystRecommendation;

    expect(validateTransactionRiskIntelligenceDetail(value)).toMatchObject({
      valid: false,
      reason: "MISSING_ANALYST_RECOMMENDATION"
    });
  });

  it("rejects malformed analyst recommendation fixtures", () => {
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationMissingFlags())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_NON_DECISIONING"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationAvailableWithoutReason())).toMatchObject({
      valid: false,
      reason: "ANALYST_RECOMMENDATION_REASON_REQUIRED"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationAvailableWithoutValue())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_VALUE"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationUnavailableWithValue())).toMatchObject({
      valid: false,
      reason: "INCONSISTENT_ANALYST_RECOMMENDATION_VALUE"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationFalseNonDecisioningFlag())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_NON_DECISIONING"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationTooManyReasonCodes())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_REASON_CODES"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationTooManyWarnings())).toMatchObject({
      valid: false,
      reason: "ANALYST_RECOMMENDATION_WARNING_LIMIT_EXCEEDED"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationMissingVersion())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_VERSION"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationBlankVersion())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_VERSION"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationMissingGeneratedAt())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_GENERATED_AT"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationAvailableWithoutGeneratedAt())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_GENERATED_AT"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationDegradedWithoutGeneratedAt())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_GENERATED_AT"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationAbsentWithInvalidGeneratedAt())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_GENERATED_AT"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationUnavailableWithInvalidGeneratedAt())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_GENERATED_AT"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationAvailableWithoutSource())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_SOURCE"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationDegradedWithoutSource())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_SOURCE"
    });
    expect(validateTransactionRiskIntelligenceDetail(malformedRecommendationNullSource())).toMatchObject({
      valid: false,
      reason: "INVALID_ANALYST_RECOMMENDATION_SOURCE"
    });
  });

  it.each([
    ["ABSENT", absentRecommendationDetail()],
    ["INSUFFICIENT_DATA", insufficientDataRecommendationDetail()],
    ["UNAVAILABLE", unavailableRecommendationDetail()]
  ])("does not normalize %s to RECOMMEND_NO_ACTION", (_status, fixture) => {
    const result = validateTransactionRiskIntelligenceDetail(fixture);

    expect(result.valid).toBe(true);
    expect(result.detail.analystRecommendation.recommendation).toBeNull();
  });

  it.each([
    ["ABSENT", absentRecommendationDetail()],
    ["NOT_APPLICABLE", notApplicableRecommendationDetail()],
    ["INSUFFICIENT_DATA", insufficientDataRecommendationDetail()],
    ["UNAVAILABLE", unavailableRecommendationDetail()]
  ])("accepts %s with generatedAt null", (_status, fixture) => {
    const result = validateTransactionRiskIntelligenceDetail(fixture);

    expect(result.valid).toBe(true);
    expect(result.detail.analystRecommendation.generatedAt).toBeNull();
  });

  it("rejects unsafe fields inside analystRecommendation", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      analystRecommendation: {
        ...analystRecommendation(),
        rawEvidence: "hidden"
      }
    }))).toMatchObject({ valid: false, reason: "UNSAFE_DETAIL_RESPONSE" });
  });

  it("rejects invalid status", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), status: "EXECUTE" }
    })).valid).toBe(false);
  });

  it("rejects arrays above display bounds", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), engines: Array.from({ length: 4 }, () => engine()) }
    }))).toMatchObject({ valid: false, reason: "ENGINE_LIMIT_EXCEEDED" });
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), diagnosticSignals: Array.from({ length: 6 }, (_, index) => signal({ reasonCode: `R${index}` })) }
    }))).toMatchObject({ valid: false, reason: "DIAGNOSTIC_SIGNAL_LIMIT_EXCEEDED" });
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), warnings: Array.from({ length: 11 }, (_, index) => ({ warningCode: `WARNING_${index}`, count: index })) }
    }))).toMatchObject({ valid: false, reason: "WARNING_LIMIT_EXCEEDED" });
  });

  it("accepts three known engine intelligence engines including velocity", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        engines: [
          engine(),
          engine({ engineId: "ml.python.primary", engineType: "ML_MODEL", riskLevel: "MEDIUM", scoreBucket: "MEDIUM", reasonCodes: ["MODEL_HIGH_RISK"] }),
          engine({ engineId: "velocity.primary", engineType: "VELOCITY", riskLevel: "HIGH", scoreBucket: "HIGH", reasonCodes: ["RAPID_PLN_20K_BURST"] })
        ],
        diagnosticSignals: [
          signal(),
          signal({
            engineId: "velocity.primary",
            engineType: "VELOCITY",
            riskLevel: "HIGH",
            scoreBucket: "HIGH",
            reasonCode: "RAPID_PLN_20K_BURST"
          })
        ]
      }
    })).valid).toBe(true);
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
    ["invalid enum", { comparisonType: "RULES_VS_ML", comparedEngineIds: ["rules.primary", "ml.python.primary"], agreementStatus: "BANANA", riskMismatchStatus: "NOT_COMPARABLE", scoreDeltaBucket: "UNAVAILABLE" }],
    ["unknown comparison type", { comparisonType: "ALL_ENGINES", comparedEngineIds: ["rules.primary", "ml.python.primary"], agreementStatus: "PARTIAL", riskMismatchStatus: "NOT_COMPARABLE", scoreDeltaBucket: "UNAVAILABLE" }],
    ["invalid compared engines", { comparisonType: "RULES_VS_ML", comparedEngineIds: ["rules.primary", "velocity.primary"], agreementStatus: "PARTIAL", riskMismatchStatus: "NOT_COMPARABLE", scoreDeltaBucket: "UNAVAILABLE" }]
  ])("rejects comparison %s", (_caseName, comparison) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), comparison }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_COMPARISON" });
  });

  it.each(sharedInvalidEngineIntelligenceCases())("rejects shared invalid semantic case $caseId", ({ engineIntelligence }) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        status: "AVAILABLE",
        ...engineIntelligence
      }
    })).valid).toBe(false);
  });

  it.each(sharedInvalidEngineIntelligenceResponseCases())("rejects shared invalid response status case $caseId", ({ engineIntelligenceResponse }) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: engineIntelligenceResponse
    })).valid).toBe(false);
  });

  it.each([
    ["offset timestamp", "2026-06-02T10:00:00+00:00"],
    ["twenty-four hour timestamp", "2026-06-02T24:00:00Z"],
    ["leap second timestamp", "2016-12-31T23:59:60Z"],
    ["year zero timestamp", "0000-01-01T00:00:00Z"],
    ["invalid calendar date", "2026-02-30T00:00:00Z"],
    ["not a date", "not-a-date"]
  ])("rejects non-canonical engine intelligence generatedAt: %s", (_caseName, generatedAt) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), generatedAt }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_METADATA" });
  });

  it.each(timestampCases())("applies shared timestamp matrix to engineIntelligence.generatedAt $caseId", ({ value, valid }) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), generatedAt: value }
    })).valid).toBe(valid);
  });

  it.each(stringBoundaryCases().filter(({ maxLength }) => maxLength === 128))("applies shared bounded-string matrix to transactionId $caseId", ({ value, valid }) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({ transactionId: value })).valid).toBe(valid);
  });

  it.each(stringBoundaryCases().filter(({ maxLength }) => maxLength === 64))("applies recommendationVersion bound $caseId", ({ value, valid }) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      analystRecommendation: analystRecommendation({ recommendationVersion: value })
    })).valid).toBe(valid);
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

  it("rejects extra public engine field", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), engines: [engine({ modelConfidence: "HIGH" })] }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_ENGINE" });
  });

  it("accepts engine null risk level for unavailable projected risk", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence({
          status: "DEGRADED",
          comparison: {
            comparisonType: "RULES_VS_ML",
            comparedEngineIds: ["rules.primary", "ml.python.primary"],
            agreementStatus: "REQUIRED_ENGINE_NOT_COMPARABLE",
            riskMismatchStatus: "NOT_COMPARABLE",
            scoreDeltaBucket: "UNAVAILABLE"
          },
          engines: [
            engine({ status: "UNAVAILABLE", riskLevel: null, scoreBucket: "UNAVAILABLE", reasonCodes: ["ORCHESTRATOR_ENGINE_TIMEOUT"] }),
            mlEngine()
          ],
          diagnosticSignals: [signal({
            engineStatus: "UNAVAILABLE",
            signalCategory: "OPERATIONAL_SIGNAL",
            riskLevel: null,
            scoreBucket: "UNAVAILABLE",
            reasonCode: "ORCHESTRATOR_ENGINE_TIMEOUT"
          })]
        })
      }
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

  it("rejects extra public diagnostic signal field", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), diagnosticSignals: [signal({ reasonCodes: ["HIGH_VELOCITY"] })] }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNAL" });
  });

  it("accepts diagnostic signal null risk level for operational diagnostics", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence({
          status: "DEGRADED",
          comparison: {
            comparisonType: "RULES_VS_ML",
            comparedEngineIds: ["rules.primary", "ml.python.primary"],
            agreementStatus: "REQUIRED_ENGINE_NOT_COMPARABLE",
            riskMismatchStatus: "NOT_COMPARABLE",
            scoreDeltaBucket: "UNAVAILABLE"
          },
          engines: [
            engine({ status: "TIMEOUT", riskLevel: null, scoreBucket: "UNAVAILABLE", reasonCodes: ["ORCHESTRATOR_ENGINE_TIMEOUT"] }),
            mlEngine()
          ]
        }),
        diagnosticSignals: [signal({
          engineStatus: "TIMEOUT",
          signalCategory: "OPERATIONAL_SIGNAL",
          riskLevel: null,
          scoreBucket: "UNAVAILABLE",
          reasonCode: "ORCHESTRATOR_ENGINE_TIMEOUT"
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

  it("rejects extra public warning field", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: {
        ...engineIntelligence(),
        warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1, source: "runtime" }]
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

  it("rejects contradictory comparison values", () => {
    const comparison = {
      comparisonType: "RULES_VS_ML",
      comparedEngineIds: ["rules.primary", "ml.python.primary"],
      agreementStatus: "AGREEMENT",
      riskMismatchStatus: "SAME_RISK_LEVEL",
      scoreDeltaBucket: "LARGE"
    };
    const result = validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), comparison }
    }));

    expect(result).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_COMPARISON" });
  });

  it.each([
    ["duplicate rules", [engine(), engine()], "INVALID_ENGINE_INTELLIGENCE_ENGINE_ORDER"],
    ["duplicate ml", [
      engine(),
      engine({ engineId: "ml.python.primary", engineType: "ML_MODEL", riskLevel: "LOW", scoreBucket: "LOW", reasonCodes: ["LOW_MODEL_RISK"] }),
      engine({ engineId: "ml.python.primary", engineType: "ML_MODEL", riskLevel: "MEDIUM", scoreBucket: "MEDIUM", reasonCodes: ["MODEL_HIGH_RISK"] })
    ], "INVALID_ENGINE_INTELLIGENCE_ENGINE_ORDER"],
    ["duplicate velocity", [
      engine(),
      engine({ engineId: "velocity.primary", engineType: "VELOCITY", riskLevel: "HIGH", scoreBucket: "HIGH", reasonCodes: ["RAPID_PLN_20K_BURST"] }),
      engine({ engineId: "velocity.primary", engineType: "VELOCITY", riskLevel: "MEDIUM", scoreBucket: "MEDIUM", reasonCodes: ["RECENT_AMOUNT_ACCUMULATION"] })
    ], "INVALID_ENGINE_INTELLIGENCE_ENGINE_ORDER"],
    ["duplicate with omitted ml", [
      engine(),
      engine(),
      engine({ engineId: "velocity.primary", engineType: "VELOCITY", riskLevel: "HIGH", scoreBucket: "HIGH", reasonCodes: ["RAPID_PLN_20K_BURST"] })
    ], "INVALID_ENGINE_INTELLIGENCE_ENGINE_ORDER"],
    ["invalid order", [
      engine({ engineId: "ml.python.primary", engineType: "ML_MODEL", riskLevel: "LOW", scoreBucket: "LOW", reasonCodes: ["LOW_MODEL_RISK"] }),
      engine()
    ], "INVALID_ENGINE_INTELLIGENCE_ENGINE_ORDER"],
    ["wrong type pair", [
      engine({ engineId: "velocity.primary", engineType: "RULES" })
    ], "INVALID_ENGINE_INTELLIGENCE_ENGINE"]
  ])("rejects invalid engine identity set: %s", (_name, engines, reason) => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), engines }
    }))).toMatchObject({ valid: false, reason });
  });

  it("rejects unsupported available contract version", () => {
    expect(validateTransactionRiskIntelligenceDetail(detail({
      engineIntelligence: { ...engineIntelligence(), contractVersion: 2 }
    }))).toMatchObject({ valid: false, reason: "INVALID_ENGINE_INTELLIGENCE_METADATA" });
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
    analystRecommendation: analystRecommendation(),
    ...overrides
  };
}

function engineIntelligence(overrides = {}) {
  return {
    status: "AVAILABLE",
    contractVersion: 1,
    generatedAt: "2026-06-18T10:00:02Z",
    comparison: {
      comparisonType: "RULES_VS_ML",
      comparedEngineIds: ["rules.primary", "ml.python.primary"],
      agreementStatus: "DISAGREEMENT",
      riskMismatchStatus: "MATERIAL_RISK_MISMATCH",
      scoreDeltaBucket: "LARGE"
    },
    engines: [engine(), mlEngine()],
    diagnosticSignals: [signal()],
    warnings: [],
    ...overrides
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

function analystRecommendation(overrides = {}) {
  return {
    status: "AVAILABLE",
    recommendation: "RECOMMEND_REVIEW",
    recommendationVersion: "analyst-recommendation-v1",
    generatedAt: "2026-06-19T10:00:00Z",
    confidence: "LOW",
    source: "RULES_RISK",
    reasonCodes: ["RULES_HIGH_RISK"],
    warnings: [],
    nonDecisioning: {
      notPaymentAuthorization: true,
      notAutomaticDecisioning: true,
      notCaseAction: true,
      notWorkflowAction: true,
      notModelPromotion: true,
      notThresholdRecommendation: true
    },
    ...overrides
  };
}

function mlEngine(overrides = {}) {
  return engine({
    engineId: "ml.python.primary",
    engineType: "ML_MODEL",
    riskLevel: "LOW",
    scoreBucket: "LOW",
    reasonCodes: ["LOW_MODEL_RISK"],
    ...overrides
  });
}

function goldenEngineIntelligence() {
  return JSON.parse(readFileSync(resolve(
    process.cwd(),
    "../common-events/src/test/resources/fixtures/engine-intelligence/engine_intelligence_three_engine_golden.json"
  ), "utf8"));
}

function sharedInvalidEngineIntelligenceCases() {
  return JSON.parse(readFileSync(resolve(
    process.cwd(),
    "../common-events/src/test/resources/fixtures/engine-intelligence/invalid_semantic_cases.json"
  ), "utf8")).cases
    .filter((semanticCase) => semanticCase.category === "engine-intelligence");
}

function sharedInvalidEngineIntelligenceResponseCases() {
  return JSON.parse(readFileSync(resolve(
    process.cwd(),
    "../common-events/src/test/resources/fixtures/engine-intelligence/invalid_semantic_cases.json"
  ), "utf8")).cases
    .filter((semanticCase) => semanticCase.category === "engine-intelligence-response");
}

function timestampCases() {
  return publicApiFixture("canonical-utc-timestamp-cases.json").cases;
}

function stringBoundaryCases() {
  return publicApiFixture("public-string-boundary-cases.json").cases;
}

function publicApiFixture(name) {
  return JSON.parse(readFileSync(resolve(process.cwd(), "../contract-fixtures/public-api", name), "utf8"));
}
