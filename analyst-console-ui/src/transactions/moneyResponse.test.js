import { afterEach, describe, expect, it, vi } from "vitest";
import { formatAmount } from "../utils/format.js";
import { isValidMoneyResponse } from "./moneyResponse.js";
import { createAlertsApiClient } from "../api/alertsApi.js";
import { createDemoAuthProvider } from "../auth/authProvider.js";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("MoneyResponse validation and presentation", () => {
  it.each([
    ["zero", { amount: 0, currency: "USD" }],
    ["zero decimal string", { amount: "0.00", currency: "USD" }],
    ["decimal string", { amount: "-12.50", currency: "PLN" }],
    ["scientific decimal", { amount: "1E+3", currency: "EUR" }],
    ["very small decimal", { amount: "1e-300", currency: "EUR" }],
    ["very large finite decimal", { amount: 1e300, currency: "JPY" }],
    ["missing amount value", { amount: null }],
    ["null amount with a currency", { amount: null, currency: "USD" }],
    ["absent response", null]
  ])("accepts %s", (_label, money) => {
    expect(isValidMoneyResponse(money)).toBe(true);
  });

  it.each([
    ["blank", { amount: "", currency: "USD" }],
    ["whitespace", { amount: " 10 ", currency: "USD" }],
    ["hexadecimal", { amount: "0x10", currency: "USD" }],
    ["NaN", { amount: "NaN", currency: "USD" }],
    ["infinity", { amount: "Infinity", currency: "USD" }],
    ["overflow", { amount: "1e999", currency: "USD" }],
    ["numeric NaN", { amount: Number.NaN, currency: "USD" }],
    ["numeric infinity", { amount: Number.POSITIVE_INFINITY, currency: "USD" }],
    ["malformed currency", { amount: 10, currency: "usd" }],
    ["currency control character", { amount: 10, currency: "US\u0000" }],
    ["missing currency", { amount: 10 }],
    ["null amount with null currency", { amount: null, currency: null }],
    ["null amount with malformed currency", { amount: null, currency: "US" }],
    ["missing amount", { currency: "USD" }],
    ["unexpected field", { amount: 10, currency: "USD", rawAmount: "10" }]
  ])("rejects %s", (_label, money) => {
    expect(isValidMoneyResponse(money)).toBe(false);
  });

  it("formats accepted values and safely handles absent or malformed values", () => {
    expect(formatAmount({ amount: "12.50", currency: "USD" })).toBe(
      new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" }).format(12.5)
    );
    expect(formatAmount({ amount: "1e-300", currency: "EUR" })).not.toBe("Not available");
    expect(formatAmount({ amount: 1e300, currency: "JPY" })).not.toBe("Not available");
    expect(formatAmount({ amount: null })).toBe("Not supplied");
    expect(formatAmount(null)).toBe("Not supplied");
    expect(formatAmount({ amount: " ", currency: "USD" })).toBe("Not available");
  });

  it("preserves decimal-string precision when formatting BigDecimal responses", () => {
    const amount = "9007199254740993.01";
    const formatter = new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" });

    expect(formatAmount({ amount, currency: "USD" })).toBe(formatter.format(amount));
    expect(formatAmount({ amount, currency: "USD" })).not.toBe(formatter.format(Number(amount)));
  });

  it("keeps supported BigDecimal wire amounts accurate through JSON parsing and display", async () => {
    const wireResponse = new Response(
      '{"content":[{"transactionId":"txn-ordinary","transactionAmount":{"amount":1249.99,"currency":"USD"}},{"transactionId":"txn-supported-maximum","transactionAmount":{"amount":999999999999.99,"currency":"USD"}},{"transactionId":"txn-outside-ingest-bound","transactionAmount":{"amount":9007199254740993.01,"currency":"USD"}}],"totalElements":3,"totalPages":1,"page":0,"size":25}',
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
    vi.spyOn(globalThis, "fetch").mockResolvedValue(wireResponse);

    const apiClient = createAlertsApiClient({
      baseUrl: "http://alerts.test",
      authProvider: createDemoAuthProvider()
    });
    const page = await apiClient.listScoredTransactions({ page: 0, size: 25 });

    expect(typeof page.content[1].transactionAmount.amount).toBe("number");
    expect(formatAmount(page.content[0].transactionAmount)).toBe(
      new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" }).format(1249.99)
    );
    expect(formatAmount(page.content[1].transactionAmount)).toBe(
      new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" }).format("999999999999.99")
    );
    expect(page.content[2].transactionAmount.amount).toBe(9007199254740994);
  });
});
