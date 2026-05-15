import { RiskBadge } from "./RiskBadge.jsx";
import { formatDateTime } from "../utils/format.js";

export function DetailHeader({
  title,
  entityType,
  entityId,
  workspaceLabel,
  status,
  riskLevel,
  actionState,
  lastLoadedAt,
  onBack,
  headingRef,
  headingId = "detail-heading"
}) {
  const breadcrumbItems = [workspaceLabel, entityType, entityId].filter(Boolean);

  return (
    <header className="detailsHeader detailHeader" aria-labelledby={headingId}>
      <div>
        <button
          className="backButton"
          type="button"
          onClick={onBack}
          aria-label={`Back to ${workspaceLabel || "workspace"}`}
        >
          Back to {workspaceLabel || "workspace"}
        </button>
        <nav className="eyebrow" aria-label="Breadcrumb">
          <ol className="breadcrumbList">
            {breadcrumbItems.map((item, index) => (
              <li key={`${item}-${index}`}>
                {index > 0 && <span aria-hidden="true"> &gt; </span>}
                <span>{item}</span>
              </li>
            ))}
          </ol>
        </nav>
        <h2 id={headingId} ref={headingRef} tabIndex="-1">{title}</h2>
        <dl className="detailHeaderMeta">
          <div><dt>Entity ID</dt><dd><code>{entityId || "Unknown"}</code></dd></div>
          {status && <div><dt>Status</dt><dd><span className="statusPill">{status}</span></dd></div>}
          {actionState && <div><dt>Actions</dt><dd>{actionState}</dd></div>}
          {lastLoadedAt && <div><dt>Last successful load</dt><dd>{formatDateTime(lastLoadedAt)}</dd></div>}
        </dl>
      </div>
      {riskLevel ? <RiskBadge riskLevel={riskLevel} /> : status ? <span className="statusPill">{status}</span> : null}
    </header>
  );
}
