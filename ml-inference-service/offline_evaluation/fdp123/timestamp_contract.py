from __future__ import annotations

from datetime import UTC, datetime
import re
from typing import Any


class TimestampContractError(ValueError):
    """Raised when an FDP timestamp is outside the shared RFC3339 contract."""


RFC3339_DATETIME_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$"
)


def normalize_rfc3339_timestamp(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise TimestampContractError(f"{field} must be a non-empty RFC3339 timestamp")
    if len(value) > 128:
        raise TimestampContractError(f"{field} exceeds maximum timestamp length")
    if RFC3339_DATETIME_PATTERN.fullmatch(value) is None:
        raise TimestampContractError(f"{field} must be an RFC3339 date-time with timezone")
    if value.endswith("Z"):
        parse_value = value[:-1] + "+00:00"
    else:
        parse_value = value
    try:
        parsed = datetime.fromisoformat(parse_value)
    except ValueError as exc:
        raise TimestampContractError(f"{field} must be a valid RFC3339 timestamp") from exc
    if parsed.tzinfo is None:
        raise TimestampContractError(f"{field} must include timezone")
    utc = parsed.astimezone(UTC)
    timespec = "microseconds" if utc.microsecond else "seconds"
    return utc.isoformat(timespec=timespec).replace("+00:00", "Z")


def timestamp_instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)
