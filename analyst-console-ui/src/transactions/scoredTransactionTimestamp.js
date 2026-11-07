const SCORED_TIMESTAMP_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.(\d{1,9}))?(Z|([+-])((?:0\d|1[0-8])):([0-5]\d))$/;

export function isValidScoredTransactionTimestamp(value) {
  if (typeof value !== "string" || value.length > 64) {
    return false;
  }
  const match = SCORED_TIMESTAMP_PATTERN.exec(value);
  if (!match) {
    return false;
  }

  const [, yearText, monthText, dayText, , , , , zone, , offsetHourText, offsetMinuteText] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth[month - 1]) {
    return false;
  }
  if (zone !== "Z" && Number(offsetHourText) === 18 && Number(offsetMinuteText) !== 0) {
    return false;
  }
  return Number.isFinite(Date.parse(value));
}
