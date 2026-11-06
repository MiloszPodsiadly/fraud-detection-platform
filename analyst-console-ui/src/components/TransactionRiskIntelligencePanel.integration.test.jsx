import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { availableDetail } from "../transactions/transactionRiskIntelligenceFixtures.js";
import { TransactionRiskIntelligencePanel } from "./TransactionRiskIntelligencePanel.jsx";

describe("TransactionRiskIntelligencePanel API integration", () => {
  it.each(["correlationId", "riskLevel"])("fails closed when the API returns an invalid %s", async (field) => {
    const rawMarker = "PRIVATE_RAW_DETAIL_CANARY";
    const response = availableDetail({ [field]: { rawMarker } });
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(response)
    };

    render(<TransactionRiskIntelligencePanel transactionId={response.transactionId} apiClient={apiClient} />);

    expect(await screen.findByText(
      "The transaction risk intelligence response is malformed or missing required safety fields."
    )).toBeInTheDocument();
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));

    expect(screen.queryByText(rawMarker)).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Transaction Summary" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Engine Intelligence Status" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Analyst Feedback" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
  });
});
