from __future__ import annotations

import json
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from offline_evaluation.dataset_schema import (
    DatasetFormatError,
    DatasetValidationError,
    MAX_DATASET_RECORDS,
    MAX_JSONL_LINE_LENGTH,
    MAX_JSONL_NON_EMPTY_LINES,
    ParsedDataset,
    validate_record_count,
    validate_metadata,
    validate_record,
)


def read_fdp102_jsonl(source: str | Path | Iterable[str]) -> ParsedDataset:
    metadata = None
    records = []
    metadata_lines = 0
    dataset_record_lines = 0
    non_empty_lines = 0
    for line_number, raw_line in enumerate(_iter_lines(source), start=1):
        line = raw_line.strip()
        if not line:
            continue
        non_empty_lines += 1
        if non_empty_lines > MAX_JSONL_NON_EMPTY_LINES:
            raise DatasetValidationError("FDP-102 JSONL exceeds maximum non-empty lines")
        if len(line) > MAX_JSONL_LINE_LENGTH:
            raise DatasetValidationError("FDP-102 JSONL line exceeds maximum length")
        try:
            payload = json.loads(line)
        except json.JSONDecodeError as exception:
            raise DatasetFormatError(f"malformed JSONL at line {line_number}") from exception
        if not isinstance(payload, dict):
            raise DatasetFormatError(f"line {line_number} must be a JSON object")
        line_type = payload.get("type")
        if non_empty_lines == 1 and line_type != "EXPORT_METADATA":
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
            if dataset_record_lines > MAX_DATASET_RECORDS:
                raise DatasetValidationError("FDP-102 JSONL exceeds maximum dataset records")
            record_payload = payload.get("record")
            if not isinstance(record_payload, dict):
                raise DatasetValidationError("DATASET_RECORD line requires a record object")
            records.append(validate_record(record_payload))
            continue
        raise DatasetFormatError(f"unknown FDP-102 JSONL line type: {line_type}")

    if non_empty_lines == 0:
        raise DatasetFormatError("FDP-102 JSONL input is empty")
    if metadata is None:
        raise DatasetFormatError("metadata line is required")
    validate_record_count(metadata, dataset_record_lines)
    return ParsedDataset(
        metadata=metadata,
        records=tuple(records),
        total_lines_read=non_empty_lines,
        metadata_lines_read=metadata_lines,
        dataset_records_read=dataset_record_lines,
    )


def _iter_lines(source: str | Path | Iterable[str]) -> Iterable[str]:
    if isinstance(source, Path):
        with source.open("r", encoding="utf-8") as handle:
            yield from handle
        return
    if isinstance(source, str):
        yield from source.splitlines()
        return
    yield from source
