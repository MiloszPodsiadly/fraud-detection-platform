export function availableEvidenceSummary() {
  return {
    caseId: "case-1",
    aggregateEvidenceStatus: "AVAILABLE",
    topReasonCodes: ["HIGH_AMOUNT_ACTIVITY"],
    highestSeverityEvidence: [{
      title: "Model signal",
      description: "Bounded model evidence metadata.",
      reasonCode: "HIGH_AMOUNT_ACTIVITY",
      evidenceType: "MODEL_SIGNAL",
      severity: "HIGH",
      source: "ML_SCORING",
      status: "AVAILABLE"
    }],
    evidenceBySource: [{ source: "ML_SCORING", count: 1 }],
    evidenceByStatus: [{ status: "AVAILABLE", count: 1 }],
    linkedAlertCount: 1,
    evidenceItemCount: 1,
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}

export function partialEvidenceSummary() {
  return {
    ...availableEvidenceSummary(),
    aggregateEvidenceStatus: "PARTIAL",
    partial: true
  };
}

export function legacyEvidenceSummary() {
  return {
    ...unavailableEvidenceSummary(),
    aggregateEvidenceStatus: "LEGACY",
    legacy: true
  };
}

export function truncatedEvidenceSummary() {
  return {
    ...availableEvidenceSummary(),
    aggregateEvidenceStatus: "PARTIAL",
    partial: true,
    truncated: true,
    truncationReason: "LINKED_ALERT_LIMIT_EXCEEDED"
  };
}

export function unavailableEvidenceSummary() {
  return {
    caseId: "case-1",
    aggregateEvidenceStatus: "UNAVAILABLE",
    topReasonCodes: [],
    highestSeverityEvidence: [],
    evidenceBySource: [],
    evidenceByStatus: [],
    linkedAlertCount: 0,
    evidenceItemCount: 0,
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}

export function maliciousTextEvidenceSummary() {
  return {
    ...availableEvidenceSummary(),
    topReasonCodes: [
      "HIGH_AMOUNT_ACTIVITY",
      "customer-secret account-secret txn-secret"
    ],
    highestSeverityEvidence: [{
      title: "customer-secret account-secret txn-secret correlation-secret alert-secret raw-model-payload scoreDetails featureSnapshot CONFIRMED_FRAUD",
      description: "source-event-secret raw-event-payload legal proof final outcome analyst decision proof of fraud",
      reasonCode: "HIGH_AMOUNT_ACTIVITY",
      evidenceType: "MODEL_SIGNAL",
      severity: "HIGH",
      source: "ML_SCORING",
      status: "AVAILABLE",
      rawPayload: "raw-model-payload",
      attributes: { customerId: "customer-secret" },
      sourceEventId: "source-event-secret",
      customerId: "customer-secret",
      accountId: "account-secret",
      transactionId: "txn-secret",
      correlationId: "correlation-secret",
      alertId: "alert-secret",
      finalOutcome: "CONFIRMED_FRAUD",
      analystDecision: "proof of fraud"
    }],
    truncationReason: "customer-secret raw truncation reason",
    extraField: "raw-event-payload"
  };
}

export function malformedCountsEvidenceSummary() {
  return {
    ...availableEvidenceSummary(),
    evidenceBySource: [
      null,
      "raw-source-secret",
      {},
      { source: "ML_SCORING", count: 1 },
      { source: "ALERT_SERVICE", count: "2" },
      { source: "raw-customer-secret", count: "not-a-number" }
    ],
    evidenceByStatus: [
      null,
      "raw-status-secret",
      {},
      { status: "AVAILABLE", count: 1 },
      { status: "PARTIAL", count: "2" },
      { status: "raw-status-with-customer-secret", count: "not-a-number" }
    ]
  };
}

export function malformedEnumEvidenceSummary() {
  return {
    ...availableEvidenceSummary(),
    topReasonCodes: [
      "HIGH_AMOUNT_ACTIVITY",
      "customer-secret account-secret txn-secret",
      "raw-model-payload-scoreDetails-featureSnapshot-too-long-too-long-too-long-too-long"
    ],
    highestSeverityEvidence: [{
      reasonCode: "customer-secret reason",
      evidenceType: "MODEL_SIGNAL",
      severity: "HIGH",
      source: "ML_SCORING",
      status: "AVAILABLE",
      title: "unsafe title should not render",
      description: "unsafe description should not render"
    }],
    evidenceBySource: [
      { source: "ML_SCORING", count: 1 },
      { source: "customer-secret source", count: 1 }
    ],
    evidenceByStatus: [
      { status: "AVAILABLE", count: 1 },
      { status: "raw status with customer-secret", count: 1 }
    ],
    truncated: true,
    truncationReason: "customer-secret raw truncation reason"
  };
}

export function rawPayloadEvidenceSummary() {
  return {
    ...availableEvidenceSummary(),
    rawPayload: "raw-model-payload",
    attributes: { customerId: "customer-secret" },
    value: "raw-value",
    baselineValue: "raw-baseline",
    scoreDetails: { payload: "scoreDetails" },
    featureSnapshot: { payload: "featureSnapshot" },
    sourceEventId: "source-event-secret",
    transactionId: "txn-secret",
    accountId: "account-secret",
    customerId: "customer-secret",
    correlationId: "correlation-secret",
    alertId: "alert-secret",
    finalOutcome: "CONFIRMED_FRAUD",
    analystDecision: "proof of fraud",
    highestSeverityEvidence: [{
      ...availableEvidenceSummary().highestSeverityEvidence[0],
      rawPayload: "raw-model-payload",
      attributes: { customerId: "customer-secret" },
      value: "raw-value",
      baselineValue: "raw-baseline",
      scoreDetails: { payload: "scoreDetails" },
      featureSnapshot: { payload: "featureSnapshot" },
      sourceEventId: "source-event-secret",
      transactionId: "txn-secret",
      accountId: "account-secret",
      customerId: "customer-secret",
      correlationId: "correlation-secret",
      alertId: "alert-secret",
      finalOutcome: "CONFIRMED_FRAUD",
      analystDecision: "proof of fraud"
    }]
  };
}
