from __future__ import annotations

import json
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from offline_evaluation.dataset_schema import (
    DatasetFormatError,
    DatasetValidationError,
    ParsedDataset,
    validate_metadata,
    validate_record,
)


def read_fdp102_jsonl(source: str | Path | Iterable[str]) -> ParsedDataset:
    lines = _lines(source)
    non_empty = [(index, line.strip()) for index, line in enumerate(lines, start=1) if line.strip()]
    if not non_empty:
        raise DatasetFormatError("FDP-102 JSONL input is empty")

    metadata = None
    records = []
    metadata_lines = 0
    dataset_record_lines = 0
    for logical_index, (line_number, line) in enumerate(non_empty):
        try:
            payload = json.loads(line)
        except json.JSONDecodeError as exception:
            raise DatasetFormatError(f"malformed JSONL at line {line_number}") from exception
        if not isinstance(payload, dict):
            raise DatasetFormatError(f"line {line_number} must be a JSON object")
        line_type = payload.get("type")
        if logical_index == 0 and line_type != "EXPORT_METADATA":
            raise DatasetFormatError("first non-empty line must be EXPORT_METADATA")
        if line_type == "EXPORT_METADATA":
            if metadata is not None:
                raise DatasetFormatError("multiple EXPORT_METADATA lines are not supported")
            metadata_lines += 1
            metadata = validate_metadata(payload)
            continue
        if line_type == "DATASET_RECORD":
            if metadata is None:
                raise DatasetFormatError("DATASET_RECORD appeared before EXPORT_METADATA")
            dataset_record_lines += 1
            record_payload = payload.get("record")
            if not isinstance(record_payload, dict):
                raise DatasetValidationError("DATASET_RECORD line requires a record object")
            records.append(validate_record(record_payload))
            continue
        raise DatasetFormatError(f"unknown FDP-102 JSONL line type: {line_type}")

    if metadata is None:
        raise DatasetFormatError("metadata line is required")
    return ParsedDataset(
        metadata=metadata,
        records=tuple(records),
        total_lines_read=len(non_empty),
        metadata_lines_read=metadata_lines,
        dataset_records_read=dataset_record_lines,
    )


def _lines(source: str | Path | Iterable[str]) -> list[str]:
    if isinstance(source, Path):
        return source.read_text(encoding="utf-8").splitlines()
    if isinstance(source, str):
        return source.splitlines()
    return list(source)
