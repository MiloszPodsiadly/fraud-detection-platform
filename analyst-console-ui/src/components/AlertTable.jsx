import { formatDateTime, formatScore } from "../utils/format.js";
import { RiskBadge } from "./RiskBadge.jsx";

export function AlertTable({ alerts, onOpenAlert }) {
  return (
    <div className="tableWrap">
      <table className="alertTable">
        <thead>
          <tr>
            <th>Risk</th>
            <th>Alert</th>
            <th>Customer</th>
            <th>Status</th>
            <th>Created</th>
            <th className="numericCell">Score</th>
            <th aria-label="Actions" />
          </tr>
        </thead>
        <tbody>
          {alerts.map((alert) => (
            <tr key={alert.alertId}>
              <td><RiskBadge riskLevel={alert.riskLevel} /></td>
              <td>
                <strong>{alert.alertReason}</strong>
                <span>{alert.transactionId}</span>
              </td>
              <td>{alert.customerId}</td>
              <td><span className="statusPill">{alert.alertStatus}</span></td>
              <td>{formatDateTime(alert.alertTimestamp)}</td>
              <td className="numericCell">{formatScore(alert.fraudScore)}</td>
              <td>
                <button className="rowButton" type="button" onClick={() => onOpenAlert(alert.alertId)}>
                  Review
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
