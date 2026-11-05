import { describe, expect, it } from "vitest";
import { formatAmount } from "../utils/format.js";
import { isValidMoneyResponse } from "./moneyResponse.js";

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
});
