const CANONICAL_UTC_TIMESTAMP_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/;

export function isCanonicalUtcTimestamp(value) {
  return parseCanonicalUtcTimestamp(value) !== null;
}

export function isOrderedCanonicalUtcTimestamp(earlier, later) {
  const earlierParts = parseCanonicalUtcTimestamp(earlier);
  const laterParts = parseCanonicalUtcTimestamp(later);
  return earlierParts !== null && laterParts !== null && compareTimestampParts(earlierParts, laterParts) <= 0;
}

function parseCanonicalUtcTimestamp(value) {
  if (typeof value !== "string" || value.length === 0 || value.length > 128) {
    return null;
  }
  const match = CANONICAL_UTC_TIMESTAMP_PATTERN.exec(value);
  if (!match) {
    return null;
  }
  const [, yearText, monthText, dayText, hourText, minuteText, secondText, fractionText = ""] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  if (year < 1 || month < 1 || month > 12 || hour > 23 || minute > 59 || second > 59) {
    return null;
  }
  if (day < 1 || day > daysInMonth(year, month)) {
    return null;
  }
  return [year, month, day, hour, minute, second, nanoseconds(fractionText)];
}

function daysInMonth(year, month) {
  if (month === 2) {
    return isLeapYear(year) ? 29 : 28;
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function nanoseconds(fractionText) {
  if (!fractionText) {
    return 0;
  }
  return Number(`${fractionText}000000000`.slice(0, 9));
}

function compareTimestampParts(left, right) {
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) {
      return left[index] < right[index] ? -1 : 1;
    }
  }
  return 0;
}
