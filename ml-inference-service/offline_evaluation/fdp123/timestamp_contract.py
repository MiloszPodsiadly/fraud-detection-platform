from __future__ import annotations

from datetime import UTC, datetime
import re
from typing import Any


class TimestampContractError(ValueError):
    """Raised when an FDP timestamp is outside the shared RFC3339 contract."""


RFC3339_DATETIME_PATTERN = re.compile(
    r"^(?P<year>\d{4})-(?P<month>\d{2})-(?P<day>\d{2})T"
    r"(?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})(?:\.\d{1,9})?Z$"
)


def normalize_rfc3339_timestamp(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise TimestampContractError(f"{field} must be a non-empty RFC3339 timestamp")
    if len(value) > 128:
        raise TimestampContractError(f"{field} exceeds maximum timestamp length")
    match = RFC3339_DATETIME_PATTERN.fullmatch(value)
    if match is None:
        raise TimestampContractError(f"{field} must be an RFC3339 date-time with timezone")
    year = int(match.group("year"))
    month = int(match.group("month"))
    hour = int(match.group("hour"))
    minute = int(match.group("minute"))
    second = int(match.group("second"))
    if year < 1:
        raise TimestampContractError(f"{field} year must be in range 0001..9999")
    if month < 1 or month > 12:
        raise TimestampContractError(f"{field} month must be in range 01..12")
    if hour > 23:
        raise TimestampContractError(f"{field} hour must be in range 00..23")
    if minute > 59:
        raise TimestampContractError(f"{field} minute must be in range 00..59")
    if second > 59:
        raise TimestampContractError(f"{field} second must be in range 00..59")
    parse_value = value[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(parse_value)
    except ValueError as exc:
        raise TimestampContractError(f"{field} must be a valid RFC3339 timestamp") from exc
    if parsed.tzinfo is None:
        raise TimestampContractError(f"{field} must include timezone")
    return value


def timestamp_instant(value: str) -> datetime:
    normalized = normalize_rfc3339_timestamp(value, "timestamp")
    return datetime.fromisoformat(normalized.replace("Z", "+00:00")).astimezone(UTC)
