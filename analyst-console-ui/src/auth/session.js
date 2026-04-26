import { AUTHORITIES } from "./generatedAuthorities.js";

export { AUTHORITIES } from "./generatedAuthorities.js";

export const ROLE_AUTHORITIES = {
  READ_ONLY_ANALYST: [
    AUTHORITIES.ALERT_READ,
    AUTHORITIES.ASSISTANT_SUMMARY_READ,
    AUTHORITIES.FRAUD_CASE_READ,
    AUTHORITIES.TRANSACTION_MONITOR_READ
  ],
  ANALYST: [
    AUTHORITIES.ALERT_READ,
    AUTHORITIES.ASSISTANT_SUMMARY_READ,
    AUTHORITIES.ALERT_DECISION_SUBMIT,
    AUTHORITIES.FRAUD_CASE_READ,
    AUTHORITIES.TRANSACTION_MONITOR_READ,
    AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE
  ],
  REVIEWER: [
    AUTHORITIES.ALERT_READ,
    AUTHORITIES.ASSISTANT_SUMMARY_READ,
    AUTHORITIES.ALERT_DECISION_SUBMIT,
    AUTHORITIES.FRAUD_CASE_READ,
    AUTHORITIES.FRAUD_CASE_UPDATE,
    AUTHORITIES.TRANSACTION_MONITOR_READ,
    AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE
  ],
  FRAUD_OPS_ADMIN: Object.values(AUTHORITIES)
};

export const AUTHORITY_LABELS = {
  [AUTHORITIES.ALERT_READ]: "alert read access",
  [AUTHORITIES.ASSISTANT_SUMMARY_READ]: "assistant summary access",
  [AUTHORITIES.ALERT_DECISION_SUBMIT]: "submit analyst decision",
  [AUTHORITIES.FRAUD_CASE_READ]: "fraud case read access",
  [AUTHORITIES.FRAUD_CASE_UPDATE]: "update fraud case",
  [AUTHORITIES.TRANSACTION_MONITOR_READ]: "transaction monitor access",
  [AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE]: "record governance advisory review"
};

export function normalizeSession(session) {
  const userId = (session.userId || "").trim();
  const roles = userId ? normalizeRoles(session.roles) : [];
  const extraAuthorities = userId ? splitValues(session.extraAuthorities) : [];
  return {
    userId,
    roles,
    extraAuthorities,
    authorities: [...new Set([
      ...roles.flatMap((role) => ROLE_AUTHORITIES[role] || []),
      ...extraAuthorities
    ])]
  };
}

export function isAuthenticated(session) {
  return Boolean(session?.userId);
}

export function hasAuthority(session, authority) {
  return Boolean(session?.authorities?.includes(authority));
}

export function authorityLabel(authority) {
  return AUTHORITY_LABELS[authority] || authority;
}

function normalizeRoles(roles) {
  const values = splitValues(roles);
  return values.filter((role) => Object.hasOwn(ROLE_AUTHORITIES, role));
}

function splitValues(value) {
  if (Array.isArray(value)) {
    return value.map(String).map((item) => item.trim()).filter(Boolean);
  }
  return splitCsv(value || "");
}

function splitCsv(value) {
  return String(value).split(",").map((item) => item.trim()).filter(Boolean);
}
