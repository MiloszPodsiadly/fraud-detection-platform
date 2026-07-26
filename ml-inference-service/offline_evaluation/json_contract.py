from __future__ import annotations

import json
import math
from typing import Any


class JsonContractError(ValueError):
    """Raised when a governance artifact uses non-standard or unsafe JSON."""


def _reject_json_constant(value: str) -> None:
    raise JsonContractError(f"non-finite JSON number is not supported: {value}")


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise JsonContractError(f"duplicate JSON object key is not supported: {key}")
        result[key] = value
    return result


def loads_strict_json(payload: str | bytes | bytearray) -> Any:
    if isinstance(payload, (bytes, bytearray)):
        payload = payload.decode("utf-8")
    return json.loads(
        payload,
        parse_constant=_reject_json_constant,
        object_pairs_hook=_reject_duplicate_keys,
    )


def dumps_strict_json(value: Any, **kwargs: Any) -> str:
    return json.dumps(value, allow_nan=False, **kwargs)


def require_finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise JsonContractError(f"{label} must be a finite number")
    result = float(value)
    if not math.isfinite(result):
        raise JsonContractError(f"{label} must be a finite number")
    return result
