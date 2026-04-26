import { EmptyState } from "./EmptyState.jsx";
import { ErrorState } from "./ErrorState.jsx";
import { LoadingPanel } from "./LoadingPanel.jsx";

const WINDOWS = [7, 14, 30];
const LIFECYCLE_STATUSES = ["OPEN", "ACKNOWLEDGED", "NEEDS_FOLLOW_UP", "DISMISSED_AS_NOISE"];

export function GovernanceAnalyticsPanel({
  analytics,
  windowDays,
  isLoading,
  error,
  onWindowDaysChange,
  onRetry
}) {
  const totals = analytics?.totals || { advisories: 0, reviewed: 0, open: 0 };
  const decisionDistribution = analytics?.decision_distribution || {};
  const lifecycleDistribution = analytics?.lifecycle_distribution || {};
  const needsFollowUpPercent = percent(decisionDistribution.NEEDS_FOLLOW_UP, totals.reviewed);
  const dismissedPercent = percent(decisionDistribution.DISMISSED_AS_NOISE, totals.reviewed);
  const maxLifecycle = Math.max(1, ...LIFECYCLE_STATUSES.map((status) => Number(lifecycleDistribution[status] || 0)));

  return (
    <section className="panel governanceAnalyticsPanel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Audit analytics</p>
          <h2>Review visibility</h2>
          <p className="sectionCopy">
            Derived read-only view of recent advisory handling. Analytics do not define SLAs, trigger actions, or change scoring.
          </p>
        </div>
        <label className="compactControl">
          Window
          <select value={windowDays} onChange={(event) => onWindowDaysChange(Number(event.target.value))}>
            {WINDOWS.map((days) => (
              <option key={days} value={days}>{days} days</option>
            ))}
          </select>
        </label>
      </div>

      {analytics?.status === "PARTIAL" && (
        <div className="stateBanner" role="status">
          Partial analytics available. Some audit or advisory data may be missing.
        </div>
      )}
      {analytics?.status === "UNAVAILABLE" && !isLoading && !error && (
        <div className="stateBanner stateBannerWarning" role="status">
          Audit analytics are currently unavailable.
        </div>
      )}

      {isLoading && <LoadingPanel label="Loading audit analytics..." />}
      {!isLoading && error && <ErrorState error={error} onRetry={onRetry} />}
      {!isLoading && !error && analytics?.status !== "UNAVAILABLE" && totals.advisories === 0 && (
        <EmptyState
          title="No advisory analytics in this window"
          message="Analytics operate on bounded recent advisory and audit history."
        />
      )}
      {!isLoading && !error && analytics?.status !== "UNAVAILABLE" && totals.advisories > 0 && (
        <>
          <div className="analyticsGrid" aria-label="Governance audit analytics summary">
            <div className="metricCard">
              <strong>{totals.advisories}</strong>
              <span>Total advisories</span>
            </div>
            <div className="metricCard">
              <strong>{totals.reviewed} / {totals.open}</strong>
              <span>Reviewed vs open</span>
            </div>
            <div className="metricCard">
              <strong>{needsFollowUpPercent}%</strong>
              <span>Needs follow-up</span>
            </div>
            <div className="metricCard">
              <strong>{dismissedPercent}%</strong>
              <span>Dismissed as noise</span>
            </div>
          </div>
          <div className="analyticsBars" aria-label="Lifecycle distribution">
            {LIFECYCLE_STATUSES.map((status) => {
              const value = Number(lifecycleDistribution[status] || 0);
              return (
                <div className="analyticsBarRow" key={status}>
                  <span>{status}</span>
                  <div className="analyticsBarTrack">
                    <div style={{ width: `${Math.max(4, (value / maxLifecycle) * 100)}%` }} />
                  </div>
                  <strong>{value}</strong>
                </div>
              );
            })}
          </div>
        </>
      )}
    </section>
  );
}

function percent(value, denominator) {
  if (!denominator) {
    return 0;
  }
  return Math.round((Number(value || 0) / denominator) * 100);
}
