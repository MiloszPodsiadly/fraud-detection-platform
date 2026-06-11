import { AnalystWorkspaceRuntime } from "./AnalystWorkspaceRuntime.jsx";
import { FraudTransactionWorkspaceRuntime } from "./FraudTransactionWorkspaceRuntime.jsx";
import { GovernanceWorkspaceRuntime } from "./GovernanceWorkspaceRuntime.jsx";
import { ReportsWorkspaceRuntime } from "./ReportsWorkspaceRuntime.jsx";
import { ShadowPerformanceWorkspaceRuntime } from "./ShadowPerformanceWorkspaceRuntime.jsx";
import { SuspiciousTransactionWorkspaceRuntime } from "./SuspiciousTransactionWorkspaceRuntime.jsx";
import { TransactionScoringWorkspaceRuntime } from "./TransactionScoringWorkspaceRuntime.jsx";

const DEFAULT_WORKSPACE_KEY = "analyst";

export const WORKSPACE_ROUTE_REGISTRY = Object.freeze({
  transactionScoring: Object.freeze({
    key: "transactionScoring",
    label: "Transaction Scoring",
    navigationLabel: "Transactions",
    routeValue: "transaction-scoring",
    href: "?workspace=transaction-scoring",
    capabilityKey: "canReadTransactions",
    heading: Object.freeze({ label: "Transaction scoring stream" }),
    Runtime: TransactionScoringWorkspaceRuntime
  }),
  fraudTransaction: Object.freeze({
    key: "fraudTransaction",
    label: "Fraud Transaction",
    navigationLabel: "Alerts",
    routeValue: "fraud-transaction",
    href: "?workspace=fraud-transaction",
    capabilityKey: "canReadAlerts",
    heading: Object.freeze({ label: "Alert review queue" }),
    Runtime: FraudTransactionWorkspaceRuntime
  }),
  suspiciousTransactions: Object.freeze({
    key: "suspiciousTransactions",
    label: "Suspicious Transactions",
    navigationLabel: "Suspicious signals",
    routeValue: "suspicious-transactions",
    href: "?workspace=suspicious-transactions",
    capabilityKey: "canReadSuspiciousTransactions",
    heading: Object.freeze({ label: "Suspicious transaction signals" }),
    Runtime: SuspiciousTransactionWorkspaceRuntime
  }),
  analyst: Object.freeze({
    key: "analyst",
    label: "Fraud Case",
    navigationLabel: "Global fraud cases",
    routeValue: "analyst",
    href: "/",
    capabilityKey: "canReadFraudCases",
    heading: Object.freeze({ label: "Fraud Case Work Queue" }),
    Runtime: AnalystWorkspaceRuntime
  }),
  reports: Object.freeze({
    key: "reports",
    label: "Reports",
    navigationLabel: "Audit analytics",
    routeValue: "reports",
    href: "?workspace=reports",
    capabilityKey: "canReadGovernanceAdvisories",
    heading: Object.freeze({ label: "Review visibility" }),
    Runtime: ReportsWorkspaceRuntime
  }),
  shadowPerformance: Object.freeze({
    key: "shadowPerformance",
    label: "Shadow Performance",
    navigationLabel: "Shadow diagnostics",
    routeValue: "shadow-performance",
    href: "?workspace=shadow-performance",
    capabilityKey: "canReadShadowPerformance",
    heading: Object.freeze({ label: "Shadow Performance Summary" }),
    showWorkspaceCounters: false,
    Runtime: ShadowPerformanceWorkspaceRuntime
  }),
  compliance: Object.freeze({
    key: "compliance",
    label: "Compliance",
    navigationLabel: "Governance",
    routeValue: "compliance",
    href: "?workspace=compliance",
    capabilityKey: "canReadGovernanceAdvisories",
    heading: Object.freeze({ label: "Operator review queue" }),
    Runtime: GovernanceWorkspaceRuntime
  })
});

export const WORKSPACE_ROUTE_ORDER = Object.freeze([
  "transactionScoring",
  "fraudTransaction",
  "suspiciousTransactions",
  "analyst",
  "reports",
  "shadowPerformance",
  "compliance"
]);

export const WORKSPACE_ROUTE_ENTRIES = Object.freeze(
  WORKSPACE_ROUTE_ORDER.map((key) => WORKSPACE_ROUTE_REGISTRY[key])
);

export function resolveWorkspaceRoute(workspaceKey) {
  return resolveWorkspaceRouteResult(workspaceKey).route;
}

export function resolveWorkspaceRouteResult(workspaceKey) {
  const route = WORKSPACE_ROUTE_REGISTRY[workspaceKey];
  if (route) {
    return { route, wasInvalid: false, requestedKey: workspaceKey || null };
  }
  return {
    route: WORKSPACE_ROUTE_REGISTRY[DEFAULT_WORKSPACE_KEY],
    wasInvalid: Boolean(workspaceKey),
    requestedKey: workspaceKey || null
  };
}

export function getWorkspaceRoute(workspaceKey) {
  return WORKSPACE_ROUTE_REGISTRY[workspaceKey] || null;
}
