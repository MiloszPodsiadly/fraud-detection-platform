import { isValidMoneyResponse } from "../transactions/moneyResponse.js";

export function formatDateTime(value) {
  if (!value) {
    return "Not available";
  }

  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}

export function formatScore(value) {
  if (value === null || value === undefined) {
    return "N/A";
  }

  return Number(value).toFixed(2);
}

export function formatAmount(money) {
  if (money === null || money === undefined) {
    return "Not supplied";
  }
  if (!isValidMoneyResponse(money)) {
    return "Not available";
  }
  if (money.amount === null) {
    return "Not supplied";
  }

  try {
    return new Intl.NumberFormat(undefined, {
      style: "currency",
      currency: money.currency
    }).format(Number(money.amount));
  } catch {
    return "Not available";
  }
}
