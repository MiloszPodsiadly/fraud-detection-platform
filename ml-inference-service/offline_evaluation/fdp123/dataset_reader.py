from __future__ import annotations

import json
from pathlib import Path

from offline_evaluation.fdp123.dataset_schema import (
    Fdp123DatasetFormatError,
    Fdp123DatasetValidationError,
    MAX_DATASET_RECORDS,
    MAX_JSONL_LINE_LENGTH,
    MAX_JSONL_NON_EMPTY_LINES,
    validate_metadata,
    validate_record_count,
    validate_record_line,
)
from offline_evaluation.fdp123.models import Fdp123Dataset


def read_fdp123_feedback_dataset_jsonl(path: str | Path) -> Fdp123Dataset:
    source = Path(path)
    if not source.exists() or not source.is_file():
        raise FileNotFoundError(f"FDP-123 JSONL input does not exist: {source}")

    metadata = None
    records = []
    metadata_lines = 0
    dataset_record_lines = 0
    non_empty_lines = 0
    with source.open("r", encoding="utf-8") as handle:
        for line_number, raw_line in enumerate(handle, start=1):
            line = raw_line.strip()
            if not line:
                continue
            non_empty_lines += 1
            if non_empty_lines > MAX_JSONL_NON_EMPTY_LINES:
                raise Fdp123DatasetValidationError("FDP-123 JSONL exceeds maximum non-empty lines")
            if len(line) > MAX_JSONL_LINE_LENGTH:
                raise Fdp123DatasetValidationError("FDP-123 JSONL line exceeds maximum length")
            try:
                payload = json.loads(line)
            except json.JSONDecodeError as exception:
                raise Fdp123DatasetFormatError(f"malformed JSONL at line {line_number}") from exception
            if not isinstance(payload, dict):
                raise Fdp123DatasetFormatError(f"line {line_number} must be a JSON object")
            line_type = payload.get("type")
            if non_empty_lines == 1 and line_type != "DATASET_METADATA":
                raise Fdp123DatasetFormatError("first non-empty line must be DATASET_METADATA")
            if line_type == "DATASET_METADATA":
                if non_empty_lines != 1:
                    raise Fdp123DatasetFormatError("DATASET_METADATA must be the first non-empty line")
                if metadata is not None:
                    raise Fdp123DatasetFormatError("multiple DATASET_METADATA lines are not supported")
                metadata_lines += 1
                metadata = validate_metadata(payload)
                continue
            if line_type == "DATASET_RECORD":
                if metadata is None:
                    raise Fdp123DatasetFormatError("DATASET_RECORD appeared before DATASET_METADATA")
                dataset_record_lines += 1
                if dataset_record_lines > MAX_DATASET_RECORDS:
                    raise Fdp123DatasetValidationError("FDP-123 JSONL exceeds maximum dataset records")
                records.append(validate_record_line(payload))
                continue
            raise Fdp123DatasetFormatError(f"unknown FDP-123 JSONL line type: {line_type}")

    if non_empty_lines == 0:
        raise Fdp123DatasetFormatError("FDP-123 JSONL input is empty")
    if metadata is None or metadata_lines != 1:
        raise Fdp123DatasetFormatError("metadata line is required")
    validate_record_count(metadata, dataset_record_lines)
    return Fdp123Dataset(metadata=metadata, records=tuple(records))

