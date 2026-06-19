export function transactionRiskIntelligencePanelId(transactionId) {
  const normalized = transactionId === null || transactionId === undefined ? "none" : String(transactionId).trim();
  const safeTransactionId = normalized
    .replace(/[^A-Za-z0-9_-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80);
  return `transaction-risk-intelligence-${safeTransactionId || "none"}`;
}
