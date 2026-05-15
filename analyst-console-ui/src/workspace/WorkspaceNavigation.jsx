export function WorkspaceNavigation({
  workspacePage,
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
    ? "Unavailable"
    : workspaceCounters.transactions ?? transactionPage.totalElements ?? 0;
  const alertGlobalCount = canReadAlerts === false
    ? "Unavailable"
    : workspaceCounters.alerts ?? alertPage.totalElements ?? 0;
  const fraudCaseGlobalCount = fraudCaseSummary?.totalFraudCases ?? fraudCaseTotalElements ?? 0;
  const fraudCaseSummaryLabel = fraudCaseSummaryError
    ? "Unavailable"
    : String(fraudCaseGlobalCount);
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

  return (
    <nav className="workspaceTabs" aria-label="Analyst workspace sections">
      <WorkspaceTab
        page="transactionScoring"
        href="?workspace=transaction-scoring"
        activePage={workspacePage}
        label="Transactions"
        value={transactionGlobalCount}
        authority={canReadTransactions}
        stale={isCounterStale("transactions", failedCounterNames, workspaceCountersStatus)}
        onWorkspaceChange={onWorkspaceChange}
      />
      <WorkspaceTab
        page="fraudTransaction"
        href="?workspace=fraud-transaction"
        activePage={workspacePage}
        label="Alerts"
        value={alertGlobalCount}
        authority={canReadAlerts}
        stale={isCounterStale("alerts", failedCounterNames, workspaceCountersStatus)}
        onWorkspaceChange={onWorkspaceChange}
      />
      <WorkspaceTab
        page="analyst"
        href="/"
        activePage={workspacePage}
        label="Global fraud cases"
        value={isFraudCaseSummaryLoading ? "..." : fraudCaseSummaryLabel}
        authority={canReadFraudCases}
        stale={Boolean(fraudCaseSummaryError)}
        title={fraudCaseSummaryHint}
        ariaLabel={`Global fraud cases ${isFraudCaseSummaryLoading ? "Loading" : fraudCaseSummaryLabel}`}
        onWorkspaceChange={onWorkspaceChange}
      />
      <WorkspaceTab
        page="reports"
        href="?workspace=reports"
        activePage={workspacePage}
        label="Audit analytics"
        value={governanceAnalytics?.totals?.advisories ?? 0}
        authority={canReadGovernanceAdvisories}
        onWorkspaceChange={onWorkspaceChange}
      />
      <WorkspaceTab
        page="compliance"
        href="?workspace=compliance"
        activePage={workspacePage}
        label="Governance"
        value={advisoryQueue.count || 0}
        authority={canReadGovernanceAdvisories}
        onWorkspaceChange={onWorkspaceChange}
      />
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
    return <small className="counterMeta">Unavailable</small>;
  }
  if (stale) {
    return <small className="counterMeta">Last known</small>;
  }
  return null;
}

function isCounterStale(counterName, failedCounterNames, workspaceCountersStatus) {
  return Boolean(workspaceCountersStatus.stale && failedCounterNames.includes(counterName));
}
