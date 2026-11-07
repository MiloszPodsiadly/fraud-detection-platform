import { WORKSPACE_ROUTE_ENTRIES } from "./WorkspaceRouteRegistry.jsx";
import { isValidWorkspaceCounterValue } from "./useWorkspaceCounters.js";

export function WorkspaceNavigation({
  workspacePage,
  workspaceRoutes = WORKSPACE_ROUTE_ENTRIES,
  showWorkspaceCounters = true,
  workspaceCounters = { alerts: null, fraudCases: null, suspiciousTransactions: null, transactions: null },
  workspaceCountersStatus = { failedCounters: [], errorByCounter: {}, stale: false },
  canReadFraudCases,
  canReadAlerts,
  canReadTransactions,
  canReadSuspiciousTransactions,
  canReadGovernanceAdvisories,
  canReadModelGovernance,
  alertPage,
  fraudCaseSummary,
  fraudCaseSummaryError,
  fraudCaseTotalElements,
  isFraudCaseSummaryLoading,
  governanceAnalytics,
  advisoryQueue,
  onWorkspaceChange
}) {
  const failedCounterNames = workspaceCountersStatus.failedCounters?.length
    ? workspaceCountersStatus.failedCounters
    : Object.keys(workspaceCountersStatus.errorByCounter || {});
  const suspiciousSummaryUnavailable = isCounterUnavailable(
    "suspiciousTransactions",
    failedCounterNames,
    workspaceCountersStatus
  ) && !isValidWorkspaceCounterValue(workspaceCounters.suspiciousTransactions);
  const suspiciousCounterUnavailable = showWorkspaceCounters
    && canReadSuspiciousTransactions === true
    && !isValidWorkspaceCounterValue(workspaceCounters.suspiciousTransactions);
  const transactionGlobalCount = showWorkspaceCounters
    ? counterValueForAuthority(canReadTransactions, workspaceCounters.transactions)
    : null;
  const alertGlobalCount = showWorkspaceCounters
    ? counterValueForAuthority(canReadAlerts, workspaceCounters.alerts ?? alertPage?.totalElements)
    : null;
  const fraudCaseSummaryCount = fraudCaseSummary?.totalFraudCases ?? fraudCaseTotalElements;
  const fraudCaseGlobalCount = showWorkspaceCounters && canReadFraudCases === true
    && isValidWorkspaceCounterValue(fraudCaseSummaryCount)
    ? fraudCaseSummaryCount
    : null;
  const fraudCaseSummaryLabel = !showWorkspaceCounters
    ? null
    : canReadFraudCases === true
      ? fraudCaseSummaryError || fraudCaseGlobalCount == null ? "Unavailable" : String(fraudCaseGlobalCount)
      : counterValueForAuthority(canReadFraudCases);
  const fraudCaseSummaryDisplayLabel = canReadFraudCases === true && isFraudCaseSummaryLoading
    ? "..."
    : fraudCaseSummaryLabel;
  const fraudCaseSummaryGeneratedAt = fraudCaseSummary?.generatedAt
    ? new Date(fraudCaseSummary.generatedAt).toLocaleString()
    : null;
  const fraudCaseSummaryHint = [
    "Global point-in-time fraud case count.",
    "It is not filter-scoped, cursor-scoped, page-scoped, or pagination metadata.",
    fraudCaseSummary?.snapshotConsistentWithWorkQueue === false
      ? "It is not snapshot-consistent with the loaded work queue slice."
      : null,
    fraudCaseSummaryGeneratedAt ? `Generated at ${fraudCaseSummaryGeneratedAt}.` : null
  ].filter(Boolean).join(" ");

  const navigationState = {
    transactionScoring: {
      value: transactionGlobalCount,
      authority: canReadTransactions,
      stale: isCounterStale("transactions", failedCounterNames, workspaceCountersStatus)
    },
    fraudTransaction: {
      value: alertGlobalCount,
      authority: canReadAlerts,
      stale: isCounterStale("alerts", failedCounterNames, workspaceCountersStatus)
    },
    suspiciousTransactions: {
      value: canReadSuspiciousTransactions === true
        ? showWorkspaceCounters ? counterValueForAuthority(canReadSuspiciousTransactions, workspaceCounters.suspiciousTransactions) : null
        : counterValueForAuthority(canReadSuspiciousTransactions),
      authority: canReadSuspiciousTransactions,
      stale: isCounterStale("suspiciousTransactions", failedCounterNames, workspaceCountersStatus),
      unavailable: suspiciousSummaryUnavailable || suspiciousCounterUnavailable,
      title: suspiciousSummaryUnavailable
        ? "Summary temporarily unavailable. Not page count, fraud count, case count, or analyst workload."
        : showWorkspaceCounters
          ? "Workspace signal total. Not page count, fraud count, case count, or analyst workload."
          : "Workspace signal counters are not shown for this diagnostic route.",
      ariaLabel: !showWorkspaceCounters
        ? undefined
        : canReadSuspiciousTransactions === false
          ? undefined
          : suspiciousSummaryUnavailable || suspiciousCounterUnavailable || canReadSuspiciousTransactions !== true
            ? "Signal total unavailable"
            : `Workspace signal total ${workspaceCounters.suspiciousTransactions}`
    },
    analyst: {
      value: fraudCaseSummaryDisplayLabel,
      authority: canReadFraudCases,
      stale: Boolean(fraudCaseSummaryError),
      title: showWorkspaceCounters ? fraudCaseSummaryHint : "Global fraud case counters are not shown for this diagnostic route.",
      ariaLabel: showWorkspaceCounters
        ? `Global fraud cases ${canReadFraudCases === true && isFraudCaseSummaryLoading ? "Loading" : fraudCaseSummaryLabel}`
        : undefined
    },
    reports: {
      value: counterValueForAuthority(canReadGovernanceAdvisories, governanceAnalytics?.totals?.advisories),
      authority: canReadGovernanceAdvisories
    },
    shadowPerformance: {
      value: canReadModelGovernance === false ? "No access" : "Current",
      authority: canReadModelGovernance,
      title: "Current offline model governance evidence."
    },
    compliance: {
      value: counterValueForAuthority(canReadGovernanceAdvisories, advisoryQueue?.count),
      authority: canReadGovernanceAdvisories
    }
  };

  return (
    <nav className="workspaceTabs" aria-label="Analyst workspace sections">
      {workspaceRoutes.map((route) => (
        <WorkspaceTab
          key={route.key}
          page={route.key}
          href={route.href}
          activePage={workspacePage}
          label={route.navigationLabel}
          value={navigationState[route.key]?.value ?? ""}
          authority={navigationState[route.key]?.authority}
          stale={navigationState[route.key]?.stale}
          unavailable={navigationState[route.key]?.unavailable}
          title={navigationState[route.key]?.title}
          ariaLabel={navigationState[route.key]?.ariaLabel}
          onWorkspaceChange={onWorkspaceChange}
        />
      ))}
    </nav>
  );
}

function WorkspaceTab({ page, href, activePage, label, value, authority, stale, unavailable, title, ariaLabel, onWorkspaceChange }) {
  return (
    <a
      href={href}
      className={activePage === page ? "workspaceTabActive" : undefined}
      aria-current={activePage === page ? "page" : undefined}
      title={title}
      aria-label={ariaLabel}
      onClick={(event) => openWorkspace(event, onWorkspaceChange, page)}
    >
      <span>{label}</span>
      <strong>{value}</strong>
      <CounterMeta authority={authority} stale={stale} unavailable={unavailable} />
    </a>
  );
}

function openWorkspace(event, onWorkspaceChange, page) {
  if (!onWorkspaceChange) {
    return;
  }
  event.preventDefault();
  onWorkspaceChange(page);
}

function CounterMeta({ authority, stale = false, unavailable = false }) {
  if (authority === false) {
    return <small className="counterMeta">Access unavailable</small>;
  }
  if (unavailable) {
    return <small className="counterMeta">Signal total unavailable</small>;
  }
  if (stale) {
    return <small className="counterMeta">Last known</small>;
  }
  return null;
}

function counterValueForAuthority(authority, value) {
  if (authority === false) {
    return "No access";
  }
  if (authority !== true) {
    return "Unavailable";
  }
  return isValidWorkspaceCounterValue(value) ? value : "Unavailable";
}

function isCounterUnavailable(counterName, failedCounterNames, workspaceCountersStatus) {
  return Boolean(workspaceCountersStatus.degraded && failedCounterNames.includes(counterName));
}

function isCounterStale(counterName, failedCounterNames, workspaceCountersStatus) {
  return Boolean(workspaceCountersStatus.stale && failedCounterNames.includes(counterName));
}
