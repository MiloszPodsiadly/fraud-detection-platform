import { WORKSPACE_ROUTE_ENTRIES } from "./WorkspaceRouteRegistry.jsx";

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
  canReadShadowPerformance,
  alertPage,
  transactionPage,
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
  ) && workspaceCounters.suspiciousTransactions == null;
  const transactionGlobalCount = !showWorkspaceCounters
    ? null
    : canReadTransactions === false
    ? "No access"
    : workspaceCounters.transactions ?? transactionPage?.totalElements ?? 0;
  const alertGlobalCount = !showWorkspaceCounters
    ? null
    : canReadAlerts === false
    ? "No access"
    : workspaceCounters.alerts ?? alertPage?.totalElements ?? 0;
  const fraudCaseGlobalCount = showWorkspaceCounters ? fraudCaseSummary?.totalFraudCases ?? fraudCaseTotalElements : null;
  const fraudCaseSummaryLabel = !showWorkspaceCounters
    ? null
    : fraudCaseSummaryError
    ? "Unavailable"
    : fraudCaseGlobalCount == null ? "0" : String(fraudCaseGlobalCount);
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
      value: canReadSuspiciousTransactions === false
        ? "No access"
        : showWorkspaceCounters ? workspaceCounters.suspiciousTransactions ?? 0 : null,
      authority: canReadSuspiciousTransactions,
      stale: isCounterStale("suspiciousTransactions", failedCounterNames, workspaceCountersStatus),
      unavailable: suspiciousSummaryUnavailable,
      title: suspiciousSummaryUnavailable
        ? "Summary temporarily unavailable. Not page count, fraud count, case count, or analyst workload."
        : showWorkspaceCounters
          ? "Workspace signal total. Not page count, fraud count, case count, or analyst workload."
          : "Workspace signal counters are not shown for this diagnostic route.",
      ariaLabel: !showWorkspaceCounters
        ? undefined
        : suspiciousSummaryUnavailable
        ? "Signal total unavailable"
        : `Workspace signal total ${workspaceCounters.suspiciousTransactions ?? 0}`
    },
    analyst: {
      value: isFraudCaseSummaryLoading ? "..." : fraudCaseSummaryLabel,
      authority: canReadFraudCases,
      stale: Boolean(fraudCaseSummaryError),
      title: showWorkspaceCounters ? fraudCaseSummaryHint : "Global fraud case counters are not shown for this diagnostic route.",
      ariaLabel: showWorkspaceCounters ? `Global fraud cases ${isFraudCaseSummaryLoading ? "Loading" : fraudCaseSummaryLabel}` : undefined
    },
    reports: {
      value: canReadGovernanceAdvisories === false
        ? "No access"
        : governanceAnalytics?.totals?.advisories ?? 0,
      authority: canReadGovernanceAdvisories
    },
    shadowPerformance: {
      value: canReadShadowPerformance === false ? "No access" : "Current",
      authority: canReadShadowPerformance,
      title: "Current offline diagnostic shadow performance summary."
    },
    compliance: {
      value: canReadGovernanceAdvisories === false
        ? "No access"
        : advisoryQueue?.count ?? 0,
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

function isCounterUnavailable(counterName, failedCounterNames, workspaceCountersStatus) {
  return Boolean(workspaceCountersStatus.degraded && failedCounterNames.includes(counterName));
}

function isCounterStale(counterName, failedCounterNames, workspaceCountersStatus) {
  return Boolean(workspaceCountersStatus.stale && failedCounterNames.includes(counterName));
}
