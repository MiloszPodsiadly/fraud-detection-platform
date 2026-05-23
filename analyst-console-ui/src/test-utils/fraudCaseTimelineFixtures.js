export function availableTimeline() {
  return {
    caseId: "case-1",
    generatedAt: "2026-05-23T10:00:00Z",
    events: [
      event({
        eventKey: "case-created",
        eventType: "FRAUD_CASE_CREATED",
        occurredAt: "2026-05-23T09:50:00Z",
        source: "ALERT_SERVICE",
        evidenceStatus: "AVAILABLE",
        linkedEntityType: "FRAUD_CASE"
      }),
      event({
        eventKey: "linked-context",
        eventType: "LINKED_ALERT_CONTEXT",
        occurredAt: "2026-05-23T10:00:00Z",
        approximateTime: true,
        source: "ALERT_SERVICE",
        evidenceStatus: "AVAILABLE",
        linkedEntityType: "FRAUD_ALERT"
      }),
      event({
        eventKey: "snapshot-available",
        eventType: "ALERT_EVIDENCE_SNAPSHOT_AVAILABLE",
        occurredAt: "2026-05-23T09:55:00Z",
        source: "FRAUD_SCORING_SERVICE",
        evidenceStatus: "AVAILABLE",
        linkedEntityType: "EVIDENCE_SNAPSHOT"
      })
    ],
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}

export function partialTimeline() {
  return {
    ...availableTimeline(),
    partial: true,
    events: [
      ...availableTimeline().events,
      event({
        eventKey: "snapshot-partial",
        eventType: "ALERT_EVIDENCE_SNAPSHOT_PARTIAL",
        occurredAt: "2026-05-23T10:05:00Z",
        source: "FRAUD_SCORING_SERVICE",
        evidenceStatus: "ERROR",
        linkedEntityType: "EVIDENCE_SNAPSHOT"
      })
    ]
  };
}

export function legacyTimeline() {
  return {
    ...emptyTimeline(),
    legacy: true,
    partial: true,
    events: [
      event({
        eventKey: "legacy-context",
        eventType: "LEGACY_CONTEXT",
        occurredAt: null,
        approximateTime: true,
        source: "ALERT_SERVICE",
        evidenceStatus: "LEGACY",
        linkedEntityType: "LEGACY_CONTEXT"
      })
    ]
  };
}

export function truncatedTimeline() {
  return {
    ...partialTimeline(),
    truncated: true,
    truncationReason: "TIMELINE_EVENT_LIMIT_EXCEEDED"
  };
}

export function emptyTimeline() {
  return {
    caseId: "case-1",
    generatedAt: "2026-05-23T10:00:00Z",
    events: [],
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}

export function emptyPartialTimeline() {
  return {
    ...emptyTimeline(),
    partial: true
  };
}

export function emptyTruncatedTimeline() {
  return {
    ...emptyTimeline(),
    partial: true,
    truncated: true,
    truncationReason: "TIMELINE_EVENT_LIMIT_EXCEEDED"
  };
}

export function emptyLegacyPartialTimeline() {
  return {
    ...emptyTimeline(),
    partial: true,
    legacy: true
  };
}

export function nullTimestampTimeline() {
  return {
    ...availableTimeline(),
    events: [
      event({
        eventKey: "null-time",
        eventType: "LINKED_ALERT_CONTEXT",
        occurredAt: null,
        approximateTime: true,
        source: "ALERT_SERVICE",
        evidenceStatus: "AVAILABLE",
        linkedEntityType: "FRAUD_ALERT"
      })
    ]
  };
}

export function uppercaseRawIdTimeline() {
  return {
    ...emptyTimeline(),
    events: [{
      eventKey: "SAFE_KEY_SHOULD_NOT_RENDER",
      eventType: "CORRELATION_ID_ABC123",
      source: "SOURCE_EVENT_20260523_ABC",
      evidenceStatus: "CUSTOMER_123456",
      linkedEntityType: "FRAUD_ALERT:ALERT_123",
      occurredAt: "2026-05-23T10:00:00Z",
      approximateTime: true,
      title: "raw title should not render",
      description: "raw description should not render"
    }]
  };
}

export function largeTimeline(size = 5000) {
  const events = Array.from({ length: size }, (_, index) => event({
    eventKey: index < 100 ? `safe-render-key-${index}` : `OMITTED_EVENT_KEY_${index}`,
    eventType: index === 0 ? "FRAUD_CASE_CREATED" : "ALERT_EVIDENCE_SNAPSHOT_AVAILABLE",
    occurredAt: "2026-05-23T10:00:00Z",
    source: "ALERT_SERVICE",
    evidenceStatus: "AVAILABLE",
    linkedEntityType: "EVIDENCE_SNAPSHOT",
    title: index >= 100 ? `omitted raw title ${index}` : "Backend title must not render",
    description: index >= 100 ? `omitted raw description ${index}` : "Backend description must not render"
  }));
  return {
    ...emptyTimeline(),
    events
  };
}

export function malformedTimeline() {
  return {
    ...availableTimeline(),
    truncated: true,
    truncationReason: "customer-secret raw truncation reason",
    events: [
      {
        eventKey: "malformed",
        eventType: "customer-secret raw type",
        occurredAt: "not-a-date",
        approximateTime: false,
        source: "source payload customer-secret",
        evidenceStatus: "status payload customer-secret",
        linkedEntityType: "entity payload customer-secret",
        title: "raw customer title",
        description: "raw payload description"
      }
    ]
  };
}

export function maliciousTimeline() {
  return {
    caseId: "case-123",
    generatedAt: "2026-05-23T10:00:00Z",
    events: [{
      eventKey: "SECRET_EVENT_KEY_SHOULD_NOT_RENDER",
      eventType: "LINKED_ALERT_CONTEXT",
      occurredAt: "2026-05-23T10:00:00Z",
      approximateTime: true,
      source: "ALERT_SERVICE",
      evidenceStatus: "AVAILABLE",
      linkedEntityType: "FRAUD_ALERT",
      title: "raw customer customer-secret",
      description: "raw payload scoreDetails featureSnapshot",
      alertId: "alert-secret",
      linkedAlertId: "linked-alert-secret",
      transactionId: "txn-secret",
      customerId: "customer-secret",
      accountId: "account-secret",
      correlationId: "correlation-secret",
      sourceEventId: "source-event-secret",
      evidenceId: "evidence-secret",
      scoreDecisionId: "score-decision-secret",
      rawPayload: "payload-secret",
      scoreDetails: "scoreDetails-secret",
      featureSnapshot: "featureSnapshot-secret",
      finalOutcome: "CONFIRMED_FRAUD",
      analystDecision: "FRAUD"
    }],
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}

function event(overrides) {
  return {
    eventKey: "event-key",
    eventType: "FRAUD_CASE_CREATED",
    occurredAt: "2026-05-23T10:00:00Z",
    source: "ALERT_SERVICE",
    evidenceStatus: "AVAILABLE",
    title: "Backend title must not render",
    description: "Backend description must not render",
    linkedEntityType: "FRAUD_CASE",
    approximateTime: false,
    ...overrides
  };
}
