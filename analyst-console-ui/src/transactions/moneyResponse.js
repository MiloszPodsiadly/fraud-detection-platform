const DECIMAL_AMOUNT = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;
const CURRENCY_CODE = /^[A-Z]{3}$/;

export function isValidMoneyResponse(money) {
  if (money === null || money === undefined) {
    return true;
  }
  if (!isRecord(money) || !Object.hasOwn(money, "amount")) {
    return false;
  }
  if (Object.keys(money).some((key) => key !== "amount" && key !== "currency")) {
    return false;
  }

  if (money.amount === null) {
    return !Object.hasOwn(money, "currency") || isCurrencyCode(money.currency);
  }
  if (typeof money.amount !== "number" && typeof money.amount !== "string") {
    return false;
  }
  if (typeof money.amount === "string" && !DECIMAL_AMOUNT.test(money.amount)) {
    return false;
  }
  if (!Number.isFinite(Number(money.amount))) {
    return false;
  }
  return Object.hasOwn(money, "currency") && isCurrencyCode(money.currency);
}

export function isCurrencyCode(value) {
  return typeof value === "string" && CURRENCY_CODE.test(value);
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
