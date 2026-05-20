import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { RiskBadge } from "../components/RiskBadge.jsx";
import { AccessDeniedPanel } from "../components/SecurityStatePanels.jsx";
import { formatDateTime, formatScore } from "../utils/format.js";

export function SuspiciousTransactionWorkspacePage({
  readViewState,
  canReadSuspiciousTransactions,
  canReadAlerts,
  selectedSuspiciousTransactionId,
  onOpenSuspiciousTransaction,
  onOpenAlert,
  onCloseSuspiciousTransaction,
  workspaceHeadingProps = {}
}) {
  if (canReadSuspiciousTransactions === false) {
    return <AccessDeniedPanel />;
  }

  if (selectedSuspiciousTransactionId) {
    return (
      <SuspiciousTransactionDetailView
        readViewState={readViewState}
        canReadAlerts={canReadAlerts}
        onOpenAlert={onOpenAlert}
        onBack={onCloseSuspiciousTransaction}
      />
    );
  }

  return (
    <SuspiciousTransactionListView
      readViewState={readViewState}
      onOpenSuspiciousTransaction={onOpenSuspiciousTransaction}
      workspaceHeadingProps={workspaceHeadingProps}
    />
  );
}

function SuspiciousTransactionListView({
  readViewState,
  onOpenSuspiciousTransaction,
  workspaceHeadingProps
}) {
  const {
    items,
    slice,
    isLoadingList,
    listError,
    refreshList,
    loadNext
  } = readViewState;

  return (
    <section className="panel" id="suspicious-transaction-read-view" aria-labelledby="suspicious-transaction-title">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Internal read view</p>
          <h2 id="suspicious-transaction-title" tabIndex="-1" {...workspaceHeadingProps}>Suspicious transaction signals</h2>
          <p className="sectionCopy">
            System-detected suspicious signal. Not confirmed fraud. Not an analyst decision. Not a final outcome.
          </p>
        </div>
        <button className="secondaryButton" type="button" onClick={refreshList}>
          Refresh
        </button>
      </div>

      <div className="workQueueToolbar suspiciousTransactionToolbar" aria-label="Suspicious transaction read semantics">
        <div className="workQueueChips">
          <span className="tag">Read-only</span>
          <span className="tag">Cursor pagination</span>
          <span className="tag">Evidence metadata only</span>
          <span className="tag">Backend authorization authoritative</span>
        </div>
      </div>

      {isLoadingList && items.length === 0 && <LoadingPanel label="Loading suspicious transaction signals..." />}
      {!isLoadingList && listError && <ErrorState error={listError} onRetry={refreshList} />}
      {!isLoadingList && !listError && items.length === 0 && (
        <EmptyState
          title="No suspicious transaction signals loaded"
          message="The current cursor slice contains no system-detected suspicious signals."
        />
      )}
      {!listError && items.length > 0 && (
        <>
          <SuspiciousTransactionTable
            items={items}
            onOpenSuspiciousTransaction={onOpenSuspiciousTransaction}
          />
          <div className="workQueueFooter suspiciousTransactionFooter">
            <span>Loaded signals in this view: {items.length}</span>
            <button
              className="secondaryButton compactButton"
              type="button"
              onClick={loadNext}
              disabled={isLoadingList || !slice.hasNext}
            >
              {slice.hasNext ? "Load next" : "No more cursor slices"}
            </button>
          </div>
        </>
      )}
    </section>
  );
}

function SuspiciousTransactionTable({ items, onOpenSuspiciousTransaction }) {
  return (
    <div className="tableWrap">
      <table className="alertTable suspiciousTransactionTable">
        <thead>
          <tr>
            <th>Risk</th>
            <th>Suspicious signal</th>
            <th>Customer</th>
            <th>Evidence metadata</th>
            <th>Status</th>
            <th>Detected</th>
            <th className="numericCell">Score</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.suspiciousTransactionId}>
              <td><RiskBadge riskLevel={item.riskLevel} /></td>
              <td>
                <strong>{item.suspiciousTransactionId}</strong>
                <span>{item.transactionId || "Transaction not supplied"}</span>
              </td>
              <td>
                <strong>{item.customerId || "Not supplied"}</strong>
                <span>{item.accountId || "Account not supplied"}</span>
              </td>
              <td>
                <strong>{item.evidenceStatus || "Not supplied"}</strong>
                <span>{evidenceMetadataSummary(item)}</span>
              </td>
              <td><span className="statusPill">{item.status || "UNKNOWN"}</span></td>
              <td>{formatDateTime(item.detectedAt)}</td>
              <td className="numericCell">{formatScore(item.riskScore)}</td>
              <td>
                <button
                  className="secondaryButton compactButton"
                  type="button"
                  onClick={() => onOpenSuspiciousTransaction(item.suspiciousTransactionId)}
                >
                  View
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SuspiciousTransactionDetailView({ readViewState, canReadAlerts, onOpenAlert, onBack }) {
  const {
    detail,
    isLoadingDetail,
    detailError,
    refreshDetail
  } = readViewState;

  return (
    <section className="panel suspiciousTransactionDetail" aria-labelledby="suspicious-transaction-detail-title">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Internal read view</p>
          <h2 id="suspicious-transaction-detail-title">Suspicious transaction signal detail</h2>
          <p className="sectionCopy">
            System-detected suspicious signal. Not confirmed fraud. Not an analyst decision. Not a final outcome.
          </p>
        </div>
        <div className="workQueueToolbarActions">
          <button className="secondaryButton compactButton" type="button" onClick={onBack}>
            Back
          </button>
          <button className="secondaryButton compactButton" type="button" onClick={refreshDetail}>
            Refresh
          </button>
        </div>
      </div>

      {isLoadingDetail && <LoadingPanel label="Loading suspicious transaction detail..." />}
      {!isLoadingDetail && detailError && <ErrorState error={detailError} onRetry={refreshDetail} />}
      {!isLoadingDetail && !detailError && detail && (
        <>
          <div className="workQueueToolbar suspiciousTransactionToolbar" aria-label="Suspicious transaction detail semantics">
            <div className="workQueueChips">
              <span className="tag">Read-only</span>
              <span className="tag">Reference view</span>
              <span className="tag">No analyst workflow</span>
              <span className="tag">No case lifecycle mutation</span>
            </div>
          </div>
          <div className="summaryGrid suspiciousTransactionSummary">
            <SummaryCard label="Risk" value={detail.riskLevel || "UNKNOWN"} helper={`Score ${formatScore(detail.riskScore)}`} />
            <SummaryCard label="Status" value={detail.status || "UNKNOWN"} helper="Read-model status only" />
            <SummaryCard label="Detected" value={formatDateTime(detail.detectedAt)} helper="System detection time" />
            <SummaryCard label="Evidence" value={detail.evidenceStatus || "Not supplied"} helper={evidenceMetadataSummary(detail)} />
          </div>
          <div className="detailGrid">
            <FieldGroup title="Identifiers">
              <Field label="Suspicious transaction ID" value={detail.suspiciousTransactionId} />
              <Field label="Transaction ID" value={detail.transactionId} />
              <Field label="Source event ID" value={detail.sourceEventId} />
              <Field label="Correlation ID" value={detail.correlationId} />
              <Field label="Customer ID" value={detail.customerId} />
              <Field label="Account ID" value={detail.accountId} />
              <Field label="Linked alert ID" value={detail.linkedAlertId || "None"} />
              <LinkedAlertContext
                linkedAlertId={detail.linkedAlertId}
                suspiciousTransactionId={detail.suspiciousTransactionId}
                canReadAlerts={canReadAlerts}
                onOpenAlert={onOpenAlert}
              />
            </FieldGroup>
            <FieldGroup title="Scoring metadata">
              <Field label="Detection source" value={detail.detectionSource} />
              <Field label="Score decision ID" value={detail.scoreDecisionId} />
              <Field label="Scoring strategy" value={detail.scoringStrategy} />
              <Field label="Model name" value={detail.modelName} />
              <Field label="Model version" value={detail.modelVersion} />
            </FieldGroup>
            <FieldGroup title="Evidence metadata">
              <Field label="Evidence status" value={detail.evidenceStatus} />
              <Field label="Evidence projection state" value={detail.evidenceProjectionState} />
              <Field label="Evidence item count" value={detail.evidenceSnapshotItemCount} />
              <Field label="Reason-code metadata" value={reasonCodes(detail.reasonCodes)} />
            </FieldGroup>
            <FieldGroup title="Read-model timestamps">
              <Field label="Created" value={formatDateTime(detail.createdAt)} />
              <Field label="Updated" value={formatDateTime(detail.updatedAt)} />
            </FieldGroup>
          </div>
        </>
      )}
    </section>
  );
}

function LinkedAlertContext({ linkedAlertId, suspiciousTransactionId, canReadAlerts, onOpenAlert }) {
  const normalizedLinkedAlertId = typeof linkedAlertId === "string" ? linkedAlertId.trim() : "";
  const normalizedSuspiciousTransactionId = typeof suspiciousTransactionId === "string" ? suspiciousTransactionId.trim() : "";
  if (!normalizedLinkedAlertId) {
    return (
      <>
        <dt>Alert context</dt>
        <dd>No linked alert</dd>
      </>
    );
  }
  if (!normalizedSuspiciousTransactionId) {
    return (
      <>
        <dt>Alert context</dt>
        <dd>Linked alert context requires a source suspicious transaction.</dd>
      </>
    );
  }
  if (canReadAlerts !== true) {
    return (
      <>
        <dt>Alert context</dt>
        <dd>Alert detail requires alert read access</dd>
      </>
    );
  }
  return (
    <>
      <dt>Alert context</dt>
      <dd>
        <button
          className="secondaryButton compactButton"
          type="button"
          onClick={() => onOpenAlert?.({
            alertId: normalizedLinkedAlertId,
            suspiciousTransactionId: normalizedSuspiciousTransactionId
          })}
        >
          View alert context
        </button>
      </dd>
    </>
  );
}

function SummaryCard({ label, value, helper }) {
  return (
    <div className="metricCard">
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{helper}</small>
    </div>
  );
}

function FieldGroup({ title, children }) {
  return (
    <section className="detailSection">
      <h3>{title}</h3>
      <dl>{children}</dl>
    </section>
  );
}

function Field({ label, value }) {
  const displayValue = value === null || value === undefined || value === "" ? "Not supplied" : String(value);
  return (
    <>
      <dt>{label}</dt>
      <dd>{displayValue}</dd>
    </>
  );
}

function evidenceMetadataSummary(item) {
  const count = item.evidenceSnapshotItemCount;
  const projection = item.evidenceProjectionState || "projection state not supplied";
  return `${count ?? "No"} evidence metadata item${count === 1 ? "" : "s"}; ${projection}`;
}

function reasonCodes(codes) {
  return Array.isArray(codes) && codes.length > 0 ? codes.join(", ") : "None supplied";
}
