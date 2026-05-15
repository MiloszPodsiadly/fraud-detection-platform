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
  headingRef
}) {
  const breadcrumb = [workspaceLabel, entityType, entityId].filter(Boolean).join(" > ");

  return (
    <header className="detailsHeader detailHeader" aria-labelledby="detail-heading">
      <div>
        <button
          className="backButton"
          type="button"
          onClick={onBack}
          aria-label={`Back to ${workspaceLabel || "workspace"}`}
        >
          Back to {workspaceLabel || "workspace"}
        </button>
        <p className="eyebrow" aria-label="Breadcrumb">{breadcrumb}</p>
        <h2 id="detail-heading" ref={headingRef} tabIndex="-1">{title}</h2>
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
