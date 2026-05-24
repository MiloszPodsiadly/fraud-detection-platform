export const INVESTIGATION_RAW_IDENTIFIER_VALUES = [
  "alert-secret",
  "linked-alert-secret",
  "txn-secret",
  "transaction-secret",
  "customer-secret",
  "account-secret",
  "correlation-secret",
  "source-event-secret",
  "evidence-secret",
  "score-decision-secret",
  "case-secret",
  "ALERT_SECRET_123",
  "LINKED_ALERT_SECRET_123",
  "TXN_999999",
  "TRANSACTION_SECRET_999999",
  "CUSTOMER_123456",
  "ACCOUNT_123456",
  "CORRELATION_ID_ABC123",
  "SOURCE_EVENT_20260523_ABC",
  "EVIDENCE_ID_ABC123",
  "SCORE_DECISION_ID_ABC123",
  "CASE_ID_SECRET_123"
];

export const INVESTIGATION_RAW_PAYLOAD_VALUES = [
  "raw-payload-secret",
  "model-payload-secret",
  "event-payload-secret",
  "scoreDetails-secret",
  "featureSnapshot-secret",
  "raw backend title",
  "raw backend description",
  "raw evidence title",
  "raw evidence description",
  "raw model explanation",
  "raw decision reason",
  "JSON.stringify",
  "attributes"
];

export const INVESTIGATION_FORBIDDEN_WORKFLOW_LABELS = [
  "Submit decision",
  "Confirm fraud",
  "Dismiss alert",
  "Resolve case",
  "Close case",
  "Reopen case",
  "As" + "sign case",
  "Cl" + "aim case",
  "Edit evidence",
  "Create evidence",
  "Link case"
];

export const INVESTIGATION_FORBIDDEN_VERDICT_PROOF_WORDING = [
  "proof of fraud",
  "fraud proof",
  "confirmed fraud",
  "fraud verdict",
  "legal proof",
  "final decision",
  "final outcome",
  "case outcome"
];

export function maliciousInvestigationRawIdentifiers() {
  return [...INVESTIGATION_RAW_IDENTIFIER_VALUES];
}

export function maliciousInvestigationRawPayloadFields() {
  return [...INVESTIGATION_RAW_PAYLOAD_VALUES];
}

export function maliciousInvestigationWorkflowLabels() {
  return [...INVESTIGATION_FORBIDDEN_WORKFLOW_LABELS];
}

export function maliciousInvestigationVerdictProofText() {
  return [...INVESTIGATION_FORBIDDEN_VERDICT_PROOF_WORDING];
}
