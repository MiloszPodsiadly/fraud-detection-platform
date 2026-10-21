import { AUTHORITIES } from "./generatedAuthorities.js";
import { MODEL_POLICY_AUTHORITY_PREFIX } from "./modelPolicyAuthorities.js";

export { AUTHORITIES } from "./generatedAuthorities.js";

export const MODEL_GOVERNANCE_READ_AUTHORITIES = Object.freeze([
  AUTHORITIES.SHADOW_PERFORMANCE_READ,
  AUTHORITIES.PROMOTION_READINESS_READ,
  AUTHORITIES.MODEL_CARD_READ,
  AUTHORITIES.MODEL_SHADOW_PERFORMANCE_READ,
  AUTHORITIES.MODEL_PROMOTION_READ
]);

export const ROLE_AUTHORITIES = {
  READ_ONLY_ANALYST: [
    AUTHORITIES.ALERT_READ,
    AUTHORITIES.ASSISTANT_SUMMARY_READ,
    AUTHORITIES.FRAUD_CASE_READ,
    AUTHORITIES.TRANSACTION_MONITOR_READ,
    AUTHORITIES.MODEL_PROMOTION_READ,
    AUTHORITIES.MODEL_PROMOTION_SCOPE_DEVELOPMENT,
    AUTHORITIES.MODEL_PROMOTION_SCOPE_TEST
  ],
  ANALYST: [
    AUTHORITIES.ALERT_READ,
    AUTHORITIES.ASSISTANT_SUMMARY_READ,
    AUTHORITIES.ALERT_DECISION_SUBMIT,
    AUTHORITIES.FRAUD_CASE_READ,
    AUTHORITIES.TRANSACTION_MONITOR_READ,
    AUTHORITIES.ENGINE_INTELLIGENCE_FEEDBACK_WRITE,
    AUTHORITIES.SUSPICIOUS_TRANSACTION_READ,
    AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE,
    AUTHORITIES.MODEL_PROMOTION_READ,
    AUTHORITIES.MODEL_PROMOTION_REGISTER,
    AUTHORITIES.MODEL_PROMOTION_PROPOSE,
    AUTHORITIES.MODEL_PROMOTION_SCOPE_DEVELOPMENT,
    AUTHORITIES.MODEL_PROMOTION_SCOPE_TEST
  ],
  REVIEWER: [
    AUTHORITIES.ALERT_READ,
    AUTHORITIES.ASSISTANT_SUMMARY_READ,
    AUTHORITIES.ALERT_DECISION_SUBMIT,
    AUTHORITIES.FRAUD_CASE_READ,
    AUTHORITIES.FRAUD_CASE_AUDIT_READ,
    AUTHORITIES.FRAUD_CASE_UPDATE,
    AUTHORITIES.TRANSACTION_MONITOR_READ,
    AUTHORITIES.ENGINE_INTELLIGENCE_FEEDBACK_READ,
    AUTHORITIES.ENGINE_INTELLIGENCE_FEEDBACK_WRITE,
    AUTHORITIES.SUSPICIOUS_TRANSACTION_READ,
    AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE,
    AUTHORITIES.MODEL_PROMOTION_READ,
    AUTHORITIES.MODEL_PROMOTION_APPROVE,
    AUTHORITIES.MODEL_PROMOTION_REJECT,
    AUTHORITIES.MODEL_PROMOTION_SCOPE_DEVELOPMENT,
    AUTHORITIES.MODEL_PROMOTION_SCOPE_TEST
  ],
  FRAUD_OPS_ADMIN: Object.values(AUTHORITIES).filter((authority) =>
    authority !== AUTHORITIES.MODEL_PROMOTION_SCOPE_PRODUCTION
      && !authority.startsWith(MODEL_POLICY_AUTHORITY_PREFIX))
};

export const AUTHORITY_LABELS = {
  [AUTHORITIES.ALERT_READ]: "alert read access",
  [AUTHORITIES.ASSISTANT_SUMMARY_READ]: "assistant summary access",
  [AUTHORITIES.ALERT_DECISION_SUBMIT]: "submit analyst decision",
  [AUTHORITIES.FRAUD_CASE_READ]: "fraud case read access",
  [AUTHORITIES.FRAUD_CASE_AUDIT_READ]: "fraud case audit read access",
  [AUTHORITIES.FRAUD_CASE_UPDATE]: "update fraud case",
  [AUTHORITIES.TRANSACTION_MONITOR_READ]: "transaction monitor access",
  [AUTHORITIES.ENGINE_INTELLIGENCE_FEEDBACK_READ]: "read engine intelligence feedback",
  [AUTHORITIES.ENGINE_INTELLIGENCE_FEEDBACK_WRITE]: "submit engine intelligence feedback",
  [AUTHORITIES.SUSPICIOUS_TRANSACTION_READ]: "suspicious transaction read access",
  [AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE]: "record governance advisory review",
  [AUTHORITIES.SHADOW_PERFORMANCE_READ]: "read shadow performance summary",
  [AUTHORITIES.MODEL_CARD_READ]: "read exact model card",
  [AUTHORITIES.MODEL_SHADOW_PERFORMANCE_READ]: "read exact model shadow performance",
  [AUTHORITIES.AUDIT_READ]: "read platform audit events"
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

export function hasAnyAuthority(session, authorities) {
  return authorities.some((authority) => hasAuthority(session, authority));
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
