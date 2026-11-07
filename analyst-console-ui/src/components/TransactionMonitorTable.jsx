import { Fragment } from "react";
import { formatAmount, formatDateTime, formatScore } from "../utils/format.js";
import { RiskBadge } from "./RiskBadge.jsx";
import { transactionRiskIntelligencePanelId } from "../transactions/transactionRiskIntelligencePanelId.js";
import { merchantDisplayValue } from "../transactions/merchantDisplay.js";
import { alertRecommendationPresentation } from "../transactions/alertRecommendationPresentation.js";

export function TransactionMonitorTable({
  transactions,
  expandedTransactionId = null,
  onToggleTransaction,
  renderTransactionDetail
}) {
  return (
    <div className="tableWrap">
      <table className="alertTable">
        <thead>
          <tr>
            <th>Risk</th>
            <th>Transaction</th>
            <th>Merchant</th>
            <th>Amount</th>
            <th>Alert recommendation</th>
            <th>Scored</th>
            <th className="numericCell">Score</th>
            <th>Detail</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((transaction) => {
            const expanded = expandedTransactionId === transaction.transactionId;
            const detailPanelId = transactionRiskIntelligencePanelId(transaction.transactionId);
            const recommendation = alertRecommendationPresentation(transaction.alertRecommended);
            return (
              <Fragment key={transaction.transactionId}>
                <tr key={transaction.transactionId}>
                  <td><RiskBadge riskLevel={transaction.riskLevel} /></td>
                  <td>
                    <strong>{transaction.transactionId}</strong>
                    <span>{transaction.customerId}</span>
                  </td>
                  <td>
                    <strong>{merchantDisplayValue(transaction.merchantInfo, "merchantName")
                      || merchantDisplayValue(transaction.merchantInfo, "merchantId") || "Unknown merchant"}</strong>
                    <span>{merchantDisplayValue(transaction.merchantInfo, "channel") || "Unknown channel"}</span>
                  </td>
                  <td>{formatAmount(transaction.transactionAmount)}</td>
                  <td>
                    <span className={`classificationPill ${recommendation.className}`}>
                      {recommendation.label}
                    </span>
                  </td>
                  <td>{formatDateTime(transaction.scoredAt)}</td>
                  <td className="numericCell">{formatScore(transaction.fraudScore)}</td>
                  <td>
                    {typeof onToggleTransaction === "function" && typeof renderTransactionDetail === "function" && (
                      <button
                        className="secondaryButton compactButton"
                        type="button"
                        aria-expanded={expanded}
                        aria-controls={detailPanelId}
                        onClick={() => onToggleTransaction(expanded ? null : transaction.transactionId)}
                      >
                        {expanded ? "Hide details" : "Details"}
                      </button>
                    )}
                  </td>
                </tr>
                {expanded && typeof renderTransactionDetail === "function" && (
                  <tr className="transactionDetailRow" key={`${transaction.transactionId}-detail`}>
                    <td colSpan={8}>{renderTransactionDetail(transaction, detailPanelId)}</td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
