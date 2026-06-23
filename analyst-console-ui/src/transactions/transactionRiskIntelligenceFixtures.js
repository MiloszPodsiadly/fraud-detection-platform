export function availableDetail(overrides = {}) {
  return mergeDetail({
    transactionId: "txn-available-1",
    correlationId: "corr-available-1",
    transactionTimestamp: "2026-06-18T10:00:00Z",
    scoredAt: "2026-06-18T10:00:01Z",
    fraudScore: 0.91,
    riskLevel: "CRITICAL",
    alertRecommended: true,
    reasonCodes: ["HIGH_VELOCITY"],
    engineIntelligence: availableEngineIntelligence(),
    analystRecommendation: availableAnalystRecommendation()
  }, overrides);
}

export function absentDetail(overrides = {}) {
  return mergeDetail(availableDetail({
    transactionId: "txn-absent-1",
    engineIntelligence: {
      status: "ABSENT",
      contractVersion: null,
      generatedAt: null,
      comparison: null,
      engines: [],
      diagnosticSignals: [],
      warnings: []
    },
    analystRecommendation: absentAnalystRecommendation()
  }), overrides);
}

export function unavailableDetail(overrides = {}) {
  return mergeDetail(availableDetail({
    transactionId: "txn-unavailable-1",
    engineIntelligence: {
      status: "UNAVAILABLE",
      contractVersion: null,
      generatedAt: null,
      comparison: null,
      engines: [],
      diagnosticSignals: [],
      warnings: []
    },
    analystRecommendation: unavailableAnalystRecommendation()
  }), overrides);
}

export function degradedDetail(overrides = {}) {
  return mergeDetail(availableDetail({
    transactionId: "txn-degraded-1",
    engineIntelligence: availableEngineIntelligence({
      status: "DEGRADED",
      engines: [{
        engineId: "rules.primary",
        engineType: "RULES",
        status: "DEGRADED",
        riskLevel: "CRITICAL",
        scoreBucket: "HIGH",
        reasonCodes: ["HIGH_VELOCITY"]
      }],
      warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1 }]
    }),
    analystRecommendation: degradedAnalystRecommendation()
  }), overrides);
}

export function comparisonNullDetail(overrides = {}) {
  return mergeDetail(availableDetail({
    transactionId: "txn-comparison-null-1",
    engineIntelligence: availableEngineIntelligence({ comparison: null })
  }), overrides);
}

export function emptyArraysDetail(overrides = {}) {
  return mergeDetail(availableDetail({
    transactionId: "txn-empty-arrays-1",
    engineIntelligence: availableEngineIntelligence({
      engines: [],
      diagnosticSignals: [],
      warnings: []
    })
  }), overrides);
}

export function malformedMissingEngineIntelligence() {
  const detail = availableDetail();
  delete detail.engineIntelligence;
  return detail;
}

export function malformedInvalidEngine() {
  return availableDetail({
    engineIntelligence: availableEngineIntelligence({
      engines: [{
        engineId: "rules.primary",
        engineType: "RULES",
        status: "EXECUTE",
        riskLevel: "CRITICAL",
        scoreBucket: "HIGH",
        reasonCodes: ["HIGH_VELOCITY"]
      }]
    })
  });
}

export function malformedInvalidWarning() {
  return availableDetail({
    engineIntelligence: availableEngineIntelligence({
      warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: -1 }]
    })
  });
}

export function availableRecommendationDetail(overrides = {}) {
  return availableDetail(overrides);
}

export function recommendReviewDetail(overrides = {}) {
  return availableDetail({
    transactionId: "txn-recommend-review-1",
    analystRecommendation: availableAnalystRecommendation({
      recommendation: "RECOMMEND_REVIEW",
      source: "RULES_RISK",
      reasonCodes: ["RULES_HIGH_RISK"]
    }),
    ...overrides
  });
}

export function recommendCaseCreationDetail(overrides = {}) {
  return availableDetail({
    transactionId: "txn-recommend-case-creation-1",
    analystRecommendation: availableAnalystRecommendation({
      recommendation: "RECOMMEND_CASE_CREATION",
      source: "RULES_RISK",
      reasonCodes: ["RAPID_TRANSFER_PATTERN", "RULES_HIGH_RISK"]
    }),
    ...overrides
  });
}

export function recommendStepUpReviewDetail(overrides = {}) {
  return availableDetail({
    transactionId: "txn-recommend-step-up-review-1",
    analystRecommendation: availableAnalystRecommendation({
      recommendation: "RECOMMEND_STEP_UP_REVIEW",
      source: "RISK_MISMATCH",
      reasonCodes: ["STEP_UP_REVIEW_CONTEXT"]
    }),
    ...overrides
  });
}

export function recommendMonitorDetail(overrides = {}) {
  return availableDetail({
    transactionId: "txn-recommend-monitor-1",
    analystRecommendation: availableAnalystRecommendation({
      recommendation: "RECOMMEND_MONITOR",
      source: "ENGINE_COMPARISON",
      reasonCodes: ["MONITORING_CONTEXT"]
    }),
    ...overrides
  });
}

export function recommendNoActionDetail(overrides = {}) {
  return availableDetail({
    transactionId: "txn-recommend-no-action-1",
    riskLevel: "LOW",
    alertRecommended: false,
    analystRecommendation: availableAnalystRecommendation({
      recommendation: "RECOMMEND_NO_ACTION",
      source: "ENGINE_COMPARISON",
      reasonCodes: ["BOTH_ENGINES_LOW_RISK", "LOW_RISK_DIAGNOSTIC_CONTEXT"]
    }),
    ...overrides
  });
}

export function absentRecommendationDetail(overrides = {}) {
  return absentDetail(overrides);
}

export function notApplicableRecommendationDetail(overrides = {}) {
  return availableDetail({
    transactionId: "txn-recommend-not-applicable-1",
    analystRecommendation: {
      status: "NOT_APPLICABLE",
      recommendation: null,
      confidence: "UNKNOWN",
      source: "NOT_APPLICABLE",
      reasonCodes: ["ENGINE_INTELLIGENCE_NO_COMPARABLE_ENGINES"],
      warnings: [],
      nonDecisioning: nonDecisioningBoundary()
    },
    ...overrides
  });
}

export function insufficientDataRecommendationDetail(overrides = {}) {
  return availableDetail({
    transactionId: "txn-recommend-insufficient-data-1",
    analystRecommendation: {
      status: "INSUFFICIENT_DATA",
      recommendation: null,
      confidence: "UNKNOWN",
      source: "ENGINE_INTELLIGENCE_ABSENT",
      reasonCodes: ["ENGINE_INTELLIGENCE_NO_ENGINES"],
      warnings: [],
      nonDecisioning: nonDecisioningBoundary()
    },
    ...overrides
  });
}

export function unavailableRecommendationDetail(overrides = {}) {
  return unavailableDetail(overrides);
}

export function degradedRecommendationDetail(overrides = {}) {
  return degradedDetail(overrides);
}

export function malformedRecommendationMissingFlags() {
  const detail = availableDetail();
  delete detail.analystRecommendation.nonDecisioning.notWorkflowAction;
  return detail;
}

export function malformedRecommendationAvailableWithoutReason() {
  return availableDetail({
    analystRecommendation: availableAnalystRecommendation({ reasonCodes: [] })
  });
}

export function malformedRecommendationAvailableWithoutValue() {
  return availableDetail({
    analystRecommendation: availableAnalystRecommendation({ recommendation: null })
  });
}

export function malformedRecommendationUnavailableWithValue() {
  return unavailableDetail({
    analystRecommendation: unavailableAnalystRecommendation({
      recommendation: "RECOMMEND_NO_ACTION"
    })
  });
}

export function malformedRecommendationFalseNonDecisioningFlag() {
  return availableDetail({
    analystRecommendation: availableAnalystRecommendation({
      nonDecisioning: {
        ...nonDecisioningBoundary(),
        notPaymentAuthorization: false
      }
    })
  });
}

export function malformedRecommendationTooManyReasonCodes() {
  return availableDetail({
    analystRecommendation: availableAnalystRecommendation({
      reasonCodes: ["R1", "R2", "R3", "R4", "R5", "R6"]
    })
  });
}

export function malformedRecommendationTooManyWarnings() {
  return availableDetail({
    analystRecommendation: availableAnalystRecommendation({
      warnings: Array.from({ length: 11 }, (_, index) => ({ warningCode: `WARNING_${index}`, count: 1 }))
    })
  });
}

export function permissionDeniedError(status = 403) {
  return { status };
}

export function notFoundError() {
  return { status: 404 };
}

export function networkError() {
  return { status: 503 };
}

export function invalidResponseError() {
  return new Error("INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE");
}

function availableEngineIntelligence(overrides = {}) {
  return {
    status: "AVAILABLE",
    contractVersion: 1,
    generatedAt: "2026-06-18T10:00:02Z",
    comparison: {
      agreementStatus: "PARTIAL",
      riskMismatchStatus: "NOT_COMPARABLE",
      scoreDeltaBucket: "UNAVAILABLE"
    },
    engines: [{
      engineId: "rules.primary",
      engineType: "RULES",
      status: "AVAILABLE",
      riskLevel: "CRITICAL",
      scoreBucket: "HIGH",
      reasonCodes: ["HIGH_VELOCITY"]
    }],
    diagnosticSignals: [{
      engineId: "rules.primary",
      engineType: "RULES",
      engineStatus: "AVAILABLE",
      signalCategory: "FRAUD_SIGNAL",
      riskLevel: "CRITICAL",
      scoreBucket: "HIGH",
      reasonCode: "HIGH_VELOCITY"
    }],
    warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1 }],
    ...overrides
  };
}

function availableAnalystRecommendation(overrides = {}) {
  return {
    status: "AVAILABLE",
    recommendation: "RECOMMEND_REVIEW",
    confidence: "LOW",
    source: "RULES_RISK",
    reasonCodes: ["RULES_HIGH_RISK"],
    warnings: [],
    nonDecisioning: nonDecisioningBoundary(),
    ...overrides
  };
}

function absentAnalystRecommendation(overrides = {}) {
  return {
    status: "ABSENT",
    recommendation: null,
    confidence: "UNKNOWN",
    source: "ENGINE_INTELLIGENCE_ABSENT",
    reasonCodes: [],
    warnings: [],
    nonDecisioning: nonDecisioningBoundary(),
    ...overrides
  };
}

function unavailableAnalystRecommendation(overrides = {}) {
  return {
    status: "UNAVAILABLE",
    recommendation: null,
    confidence: "UNKNOWN",
    source: "ENGINE_INTELLIGENCE_UNAVAILABLE",
    reasonCodes: [],
    warnings: [],
    nonDecisioning: nonDecisioningBoundary(),
    ...overrides
  };
}

function degradedAnalystRecommendation(overrides = {}) {
  return {
    status: "DEGRADED",
    recommendation: "RECOMMEND_REVIEW",
    confidence: "LOW",
    source: "ENGINE_INTELLIGENCE_DEGRADED",
    reasonCodes: ["RULES_HIGH_RISK"],
    warnings: [{ warningCode: "ENGINE_INTELLIGENCE_DEGRADED", count: 1 }],
    nonDecisioning: nonDecisioningBoundary(),
    ...overrides
  };
}

function nonDecisioningBoundary() {
  return {
    notPaymentAuthorization: true,
    notAutomaticDecisioning: true,
    notCaseAction: true,
    notWorkflowAction: true,
    notModelPromotion: true,
    notThresholdRecommendation: true
  };
}

function mergeDetail(base, overrides) {
  return {
    ...base,
    ...overrides,
    engineIntelligence: overrides.engineIntelligence || base.engineIntelligence,
    analystRecommendation: overrides.analystRecommendation || base.analystRecommendation
  };
}
