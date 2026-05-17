import { WORKSPACE_ROUTE_ENTRIES } from "./WorkspaceRouteRegistry.jsx";

export function WorkspaceNavigation({
  workspacePage,
  workspaceRoutes = WORKSPACE_ROUTE_ENTRIES,
  workspaceCounters = { alerts: null, transactions: null },
  workspaceCountersStatus = { failedCounters: [], errorByCounter: {}, stale: false },
  canReadFraudCases,
  canReadAlerts,
  canReadTransactions,
  canReadGovernanceAdvisories,
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
  const transactionGlobalCount = canReadTransactions === false
    ? "No access"
    : workspaceCounters.transactions ?? transactionPage?.totalElements ?? "Unavailable";
  const alertGlobalCount = canReadAlerts === false
    ? "No access"
    : workspaceCounters.alerts ?? alertPage?.totalElements ?? "Unavailable";
  const fraudCaseGlobalCount = fraudCaseSummary?.totalFraudCases ?? fraudCaseTotalElements;
  const fraudCaseSummaryLabel = fraudCaseSummaryError
    ? "Unavailable"
    : fraudCaseGlobalCount == null ? "Unavailable" : String(fraudCaseGlobalCount);
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
    analyst: {
      value: isFraudCaseSummaryLoading ? "..." : fraudCaseSummaryLabel,
      authority: canReadFraudCases,
      stale: Boolean(fraudCaseSummaryError),
      title: fraudCaseSummaryHint,
      ariaLabel: `Global fraud cases ${isFraudCaseSummaryLoading ? "Loading" : fraudCaseSummaryLabel}`
    },
    reports: {
      value: canReadGovernanceAdvisories === false
        ? "No access"
        : governanceAnalytics?.totals?.advisories ?? "Unavailable",
      authority: canReadGovernanceAdvisories
    },
    compliance: {
      value: canReadGovernanceAdvisories === false
        ? "No access"
        : advisoryQueue?.count ?? "Unavailable",
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
          value={navigationState[route.key]?.value ?? 0}
          authority={navigationState[route.key]?.authority}
          stale={navigationState[route.key]?.stale}
          title={navigationState[route.key]?.title}
          ariaLabel={navigationState[route.key]?.ariaLabel}
          onWorkspaceChange={onWorkspaceChange}
        />
      ))}
    </nav>
  );
}

function WorkspaceTab({ page, href, activePage, label, value, authority, stale, title, ariaLabel, onWorkspaceChange }) {
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
      <CounterMeta authority={authority} stale={stale} />
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

function CounterMeta({ authority, stale = false }) {
  if (authority === false) {
    return <small className="counterMeta">Access unavailable</small>;
  }
  if (stale) {
    return <small className="counterMeta">Last known</small>;
  }
  return null;
}

function isCounterStale(counterName, failedCounterNames, workspaceCountersStatus) {
  return Boolean(workspaceCountersStatus.stale && failedCounterNames.includes(counterName));
}
