import { isValidMoneyResponse } from "../transactions/moneyResponse.js";

export function formatDateTime(value) {
  if (!value) {
    return "Not available";
  }

  try {
    const date = value instanceof Date ? value : new Date(value);
    if (!Number.isFinite(date.getTime()) || !hasPossibleIsoCalendarDate(value)) {
      return "Not available";
    }

    return new Intl.DateTimeFormat(undefined, {
      dateStyle: "medium",
      timeStyle: "short"
    }).format(date);
  } catch {
    return "Not available";
  }
}

function hasPossibleIsoCalendarDate(value) {
  if (typeof value !== "string") {
    return true;
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:T|$)/.exec(value);
  if (!match) {
    return true;
  }

  const [, yearText, monthText, dayText] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth[month - 1];
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
    }).format(money.amount);
  } catch {
    return "Not available";
  }
}
