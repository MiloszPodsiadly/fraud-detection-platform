import { formatAmount, formatDateTime, formatScore } from "../utils/format.js";
import { RiskBadge } from "./RiskBadge.jsx";

export function TransactionMonitorTable({ transactions }) {
  return (
    <div className="tableWrap">
      <table className="alertTable">
        <thead>
          <tr>
            <th>Risk</th>
            <th>Transaction</th>
            <th>Merchant</th>
            <th>Amount</th>
            <th>Score</th>
            <th>Classification</th>
            <th>Scored</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((transaction) => (
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
              <td>{formatScore(transaction.fraudScore)}</td>
              <td>
                <span className={`classificationPill ${transaction.alertRecommended ? "classificationReview" : "classificationClear"}`}>
                  {transaction.alertRecommended ? "Suspicious" : "Legitimate"}
                </span>
              </td>
              <td>{formatDateTime(transaction.scoredAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
