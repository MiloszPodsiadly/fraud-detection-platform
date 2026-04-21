import { formatAmount } from "../utils/format.js";

export function TransactionSummary({ alert }) {
  return (
    <section className="subPanel">
      <h3>Transaction summary</h3>
      <div className="summaryGrid">
        <SummaryItem label="Amount" value={formatAmount(alert.transactionAmount)} />
        <SummaryItem label="Merchant" value={alert.merchantInfo?.merchantName || alert.merchantInfo?.merchantId} />
        <SummaryItem label="MCC" value={alert.merchantInfo?.merchantCategoryCode} />
        <SummaryItem label="Channel" value={alert.merchantInfo?.channel} />
        <SummaryItem label="Device" value={alert.deviceInfo?.deviceId} />
        <SummaryItem label="IP address" value={alert.deviceInfo?.ipAddress} />
        <SummaryItem label="Location" value={[alert.locationInfo?.city, alert.locationInfo?.countryCode].filter(Boolean).join(", ")} />
        <SummaryItem label="Segment" value={alert.customerContext?.segment} />
      </div>
    </section>
  );
}

function SummaryItem({ label, value }) {
  return (
    <div className="summaryItem">
      <span>{label}</span>
      <strong>{value || "Not supplied"}</strong>
    </div>
  );
}
