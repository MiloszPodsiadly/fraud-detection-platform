import { useMemo } from "react";
import { createAlertsApiClient } from "../api/alertsApi.js";
import { AUTHORITIES, MODEL_GOVERNANCE_READ_AUTHORITIES, hasAuthority } from "../auth/session.js";
import { MODEL_POLICY_AUTHORITIES as POLICY } from "../auth/modelPolicyAuthorities.js";
import { WorkspaceRuntimeContext } from "./useWorkspaceRuntime.js";

export function WorkspaceRuntimeProvider({ session, authProvider, enabled = true, children }) {
  const authenticated = Boolean(session?.userId);
  const apiClient = useMemo(
    () => (enabled && authenticated ? createAlertsApiClient({ session, authProvider }) : null),
    [authProvider, authenticated, enabled, session]
  );
  const workspaceSessionResetKey = useMemo(
    () => workspaceSessionResetKeyFor(session, authProvider),
    [authProvider, session]
  );
  const shadowPerformanceRead = authorityState(session, AUTHORITIES.SHADOW_PERFORMANCE_READ);
  const promotionReadinessRead = authorityState(session, AUTHORITIES.PROMOTION_READINESS_READ);
  const modelCardRead = authorityState(session, AUTHORITIES.MODEL_CARD_READ);
  const modelShadowPerformanceRead = authorityState(session, AUTHORITIES.MODEL_SHADOW_PERFORMANCE_READ);
  const modelGovernanceRead = authorityGroupState(session, MODEL_GOVERNANCE_READ_AUTHORITIES);
  // Capability flags are frontend UX/request gates only. Backend authorization still enforces every protected API call.
  const value = useMemo(() => ({
    session,
    authProvider,
    apiClient,
    workspaceSessionResetKey,
    canReadAlerts: authorityState(session, AUTHORITIES.ALERT_READ),
    canReadFraudCases: authorityState(session, AUTHORITIES.FRAUD_CASE_READ),
    canReadTransactions: authorityState(session, AUTHORITIES.TRANSACTION_MONITOR_READ),
    canReadSuspiciousTransactions: authorityState(session, AUTHORITIES.SUSPICIOUS_TRANSACTION_READ),
    canReadModelGovernance: modelGovernanceRead,
    canReadShadowPerformance: shadowPerformanceRead,
    canReadPromotionReadiness: promotionReadinessRead,
    canReadModelCard: modelCardRead,
    canReadExactModelShadowPerformance: modelShadowPerformanceRead,
    canReadModelPromotion: authorityState(session, AUTHORITIES.MODEL_PROMOTION_READ),
    canReadPolicyGovernance: authorityState(session, POLICY.READ),
    canReadPolicySecurity: authorityGroupState(session, [
      POLICY.CREDENTIAL_ENROLL, POLICY.CREDENTIAL_ADMIN, POLICY.TRUST_READ,
      POLICY.TRUST_REQUEST, POLICY.TRUST_APPROVE, POLICY.PUBLICATION_RECOVER
    ]),
    canRegisterModelPromotion: authorityState(session, AUTHORITIES.MODEL_PROMOTION_REGISTER),
    canProposeModelPromotion: authorityState(session, AUTHORITIES.MODEL_PROMOTION_PROPOSE),
    canApproveModelPromotion: authorityState(session, AUTHORITIES.MODEL_PROMOTION_APPROVE),
    canRejectModelPromotion: authorityState(session, AUTHORITIES.MODEL_PROMOTION_REJECT),
    canRetireModelPromotion: authorityState(session, AUTHORITIES.MODEL_PROMOTION_RETIRE),
    canRequestEmergencyModelRetirement: authorityState(session, AUTHORITIES.MODEL_PROMOTION_EMERGENCY_REQUEST),
    canAuthorizeEmergencyModelRetirement: authorityState(session, AUTHORITIES.MODEL_PROMOTION_EMERGENCY_AUTHORIZE),
    canAuditEmergencyModelRetirement: authorityState(session, POLICY.AUDIT_READ) === true
      && authorityState(session, AUTHORITIES.MODEL_PROMOTION_SCOPE_PRODUCTION) === true,
    // Governance advisory read views are backed by TRANSACTION_MONITOR_READ in the current backend authorization model.
    // Governance audit writes require GOVERNANCE_ADVISORY_AUDIT_WRITE; do not reuse read capability for write affordances.
    canReadGovernanceAdvisories: authorityState(session, AUTHORITIES.TRANSACTION_MONITOR_READ),
    canWriteGovernanceAudit: authorityState(session, AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE),
    runtimeStatus: enabled && authenticated ? "ready" : "disabled"
  }), [
    apiClient,
    authProvider,
    enabled,
    authenticated,
    modelCardRead,
    modelShadowPerformanceRead,
    modelGovernanceRead,
    promotionReadinessRead,
    session,
    shadowPerformanceRead,
    workspaceSessionResetKey
  ]);

  return (
    <WorkspaceRuntimeContext.Provider value={value}>
      {children}
    </WorkspaceRuntimeContext.Provider>
  );
}

function authorityState(session, authority) {
  return authorityGroupState(session, [authority]);
}

function authorityGroupState(session, authorities) {
  if (!session?.userId) {
    return undefined;
  }
  if (!Array.isArray(session.authorities) || session.authorities.length === 0) {
    return undefined;
  }
  return authorities.some((authority) => hasAuthority(session, authority));
}

function workspaceSessionResetKeyFor(session, authProvider) {
  const roles = Array.isArray(session?.roles) ? session.roles.join(",") : "";
  const authorities = Array.isArray(session?.authorities) ? session.authorities.join(",") : "";
  return [
    authProvider?.kind || "none",
    session?.userId || "",
    roles,
    authorities
  ].join(":");
}
