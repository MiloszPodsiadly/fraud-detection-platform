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
    engineIntelligence: availableEngineIntelligence()
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
    }
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
    }
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
    })
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

function mergeDetail(base, overrides) {
  return {
    ...base,
    ...overrides,
    engineIntelligence: overrides.engineIntelligence || base.engineIntelligence
  };
}
