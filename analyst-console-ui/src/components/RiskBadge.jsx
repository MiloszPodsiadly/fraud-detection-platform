export function RiskBadge({ riskLevel }) {
  const level = riskLevel || "UNKNOWN";
  return <span className={`riskBadge risk${level}`}>{level}</span>;
}
