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
  if (!money || money.amount === null || money.amount === undefined) {
    return "Not supplied";
  }

  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: money.currency || "USD"
  }).format(Number(money.amount));
}
