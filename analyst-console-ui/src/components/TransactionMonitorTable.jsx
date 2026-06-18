import { Fragment } from "react";
import { formatAmount, formatDateTime, formatScore } from "../utils/format.js";
import { RiskBadge } from "./RiskBadge.jsx";

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
            <th>Classification</th>
            <th>Scored</th>
            <th className="numericCell">Score</th>
            <th>Detail</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((transaction) => {
            const expanded = expandedTransactionId === transaction.transactionId;
            return (
              <Fragment key={transaction.transactionId}>
                <tr key={transaction.transactionId}>
                  <td><RiskBadge riskLevel={transaction.riskLevel} /></td>
                  <td>
                    <strong>{transaction.transactionId}</strong>
                    <span>{transaction.customerId}</span>
                  </td>
                  <td>
                    <strong>{transaction.merchantInfo?.merchantName || transaction.merchantInfo?.merchantId || "Unknown merchant"}</strong>
                    <span>{transaction.merchantInfo?.channel || "Unknown channel"}</span>
                  </td>
                  <td>{formatAmount(transaction.transactionAmount)}</td>
                  <td>
                    <span className={`classificationPill ${transaction.alertRecommended ? "classificationReview" : "classificationClear"}`}>
                      {transaction.alertRecommended ? "Suspicious" : "Legitimate"}
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
                        onClick={() => onToggleTransaction(expanded ? null : transaction.transactionId)}
                      >
                        {expanded ? "Hide details" : "Details"}
                      </button>
                    )}
                  </td>
                </tr>
                {expanded && typeof renderTransactionDetail === "function" && (
                  <tr className="transactionDetailRow" key={`${transaction.transactionId}-detail`}>
                    <td colSpan={8}>{renderTransactionDetail(transaction)}</td>
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
