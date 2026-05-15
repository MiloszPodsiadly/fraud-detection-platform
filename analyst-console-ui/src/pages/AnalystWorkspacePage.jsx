import { FraudCaseWorkQueuePanel } from "../components/FraudCaseWorkQueuePanel.jsx";

export function AnalystWorkspacePage({
  canReadFraudCases,
  fraudCaseSummaryError,
  fraudCaseWorkQueue,
  fraudCaseWorkQueueRequest,
  fraudCaseWorkQueueDraftFilters,
  fraudCaseWorkQueueWarning,
  fraudCaseWorkQueueFilterError,
  fraudCaseWorkQueueLastRefreshedAt,
  isFraudCaseWorkQueueLoading,
  fraudCaseWorkQueueError,
  onFraudCaseSummaryRetry,
  onFraudCaseWorkQueueDraftChange,
  onFraudCaseWorkQueueApplyFilters,
  onFraudCaseWorkQueueResetFilters,
  onFraudCaseWorkQueueRetry,
  onFraudCaseWorkQueueRefreshFirstSlice,
  onFraudCaseWorkQueueLoadMore,
  onOpenFraudCase,
  workspaceHeadingProps = {}
}) {
  if (canReadFraudCases === false) {
    return (
      <div className="statePanel" role="alert">
        <h3>Fraud case access denied.</h3>
        <p>This session does not include fraud case read authority.</p>
      </div>
    );
  }

  return (
    <>
      <FraudCaseWorkQueuePanel
        queue={fraudCaseWorkQueue}
        request={fraudCaseWorkQueueRequest}
        draftRequest={fraudCaseWorkQueueDraftFilters}
        isLoading={isFraudCaseWorkQueueLoading}
        error={fraudCaseWorkQueueError}
        warning={fraudCaseWorkQueueWarning}
        validationError={fraudCaseWorkQueueFilterError}
        lastRefreshedAt={fraudCaseWorkQueueLastRefreshedAt}
        onDraftChange={onFraudCaseWorkQueueDraftChange}
        onApplyFilters={onFraudCaseWorkQueueApplyFilters}
        onResetFilters={onFraudCaseWorkQueueResetFilters}
        onLoadMore={onFraudCaseWorkQueueLoadMore}
        onRetry={onFraudCaseWorkQueueRetry}
        onRefreshFirstSlice={onFraudCaseWorkQueueRefreshFirstSlice}
        onOpenCase={onOpenFraudCase}
        headingProps={workspaceHeadingProps}
      />

      {fraudCaseSummaryError && (
        <div className="statePanel warningPanel" role="status">
          <h3>Global fraud case count unavailable.</h3>
          <p>The work queue can still load. Retry only the global point-in-time summary.</p>
          <button className="secondaryButton" type="button" onClick={onFraudCaseSummaryRetry}>
            Retry summary
          </button>
        </div>
      )}
    </>
  );
}
