from __future__ import annotations

from datetime import datetime
import re
from typing import Any


class TimestampContractError(ValueError):
    """Raised when an FDP timestamp is outside the shared RFC3339 contract."""


RFC3339_DATETIME_PATTERN = re.compile(
    r"^(?P<year>\d{4})-(?P<month>\d{2})-(?P<day>\d{2})T"
    r"(?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})"
    r"(?:\.(?P<fraction>\d{1,9}))?Z$"
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


def compare_rfc3339_timestamps(left: str, right: str) -> int:
    left_key = _timestamp_order_key(left, "left timestamp")
    right_key = _timestamp_order_key(right, "right timestamp")
    return (left_key > right_key) - (left_key < right_key)


def _timestamp_order_key(value: str, field: str) -> tuple[int, int, int, int, int, int, int]:
    normalized = normalize_rfc3339_timestamp(value, field)
    match = RFC3339_DATETIME_PATTERN.fullmatch(normalized)
    if match is None:
        raise TimestampContractError(f"{field} must be an RFC3339 date-time with timezone")
    fraction = (match.group("fraction") or "").ljust(9, "0")
    return (
        int(match.group("year")),
        int(match.group("month")),
        int(match.group("day")),
        int(match.group("hour")),
        int(match.group("minute")),
        int(match.group("second")),
        int(fraction or "0"),
    )


def validate_optional_timestamp_range(
        from_inclusive: Any,
        to_inclusive: Any,
        from_field: str = "fromInclusive",
        to_field: str = "toInclusive",
) -> tuple[str | None, str | None]:
    normalized_from = (
        None
        if from_inclusive is None
        else normalize_rfc3339_timestamp(from_inclusive, from_field)
    )
    normalized_to = (
        None
        if to_inclusive is None
        else normalize_rfc3339_timestamp(to_inclusive, to_field)
    )
    if (
            normalized_from is not None
            and normalized_to is not None
            and compare_rfc3339_timestamps(normalized_from, normalized_to) > 0
    ):
        raise TimestampContractError(f"{from_field} must not be later than {to_field}")
    return normalized_from, normalized_to
