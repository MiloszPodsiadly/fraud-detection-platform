from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime
from random import Random
from typing import Any

from app.data.dataset import Dataset

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class DatasetSplits:
    """Train/validation/test datasets plus split metadata."""

    train: Dataset
    validation: Dataset
    test: Dataset
    metadata: dict[str, Any]


def split_dataset(
        dataset: Dataset,
        train_ratio: float = 0.6,
        validation_ratio: float = 0.2,
        mode: str = "temporal",
        min_fraud_per_split: int = 1,
        fraud_rate_tolerance: float = 0.05,
        cutoff_ratio: float | None = None,
        cutoff_timestamp: str | None = None,
        seed: int = 7341,
) -> DatasetSplits:
    """Split a dataset for fraud modelling with temporal and class-balance metadata."""
    if dataset.size < 3:
        raise ValueError("dataset must contain at least 3 rows for train/validation/test split.")
    if mode not in {"random", "temporal", "out_of_time"}:
        raise ValueError("split mode must be 'random', 'temporal', or 'out_of_time'.")
    indexed_rows = list(enumerate(zip(dataset.X, dataset.y)))
    temporal = all(_timestamp(row[0]) is not None for _, row in indexed_rows)
    if mode in {"temporal", "out_of_time"} and temporal:
        indexed_rows.sort(key=lambda item: (_timestamp(item[1][0]), item[0]))
    elif mode == "random":
        Random(seed).shuffle(indexed_rows)

    if mode == "temporal" and _can_stratify(indexed_rows, min_fraud_per_split):
        train_rows, validation_rows, test_rows = _stratified_temporal_split(indexed_rows, train_ratio, validation_ratio)
        strategy = "stratified_temporal"
    else:
        if mode == "temporal" and not _can_stratify(indexed_rows, min_fraud_per_split):
            log.warning("Falling back to ordered temporal split because fraud cases are too sparse for stratification.")
        train_rows, validation_rows, test_rows = _ordered_split(
            indexed_rows,
            train_ratio,
            validation_ratio,
            cutoff_ratio=cutoff_ratio,
            cutoff_timestamp=cutoff_timestamp if mode == "out_of_time" else None,
        )
        strategy = "out_of_time" if mode == "out_of_time" else ("random" if mode == "random" else "temporal_fallback")

    _warn_if_imbalanced(train_rows, validation_rows, test_rows, fraud_rate_tolerance)
    metadata = {
        "strategy": strategy,
        "mode": mode,
        "rows": dataset.size,
        "trainRows": len(train_rows),
        "validationRows": len(validation_rows),
        "testRows": len(test_rows),
        "trainIndices": [index for index, _ in train_rows],
        "validationIndices": [index for index, _ in validation_rows],
        "testIndices": [index for index, _ in test_rows],
        "classDistribution": {
            "train": _class_distribution(train_rows),
            "validation": _class_distribution(validation_rows),
            "test": _class_distribution(test_rows),
        },
        "fraudRate": {
            "train": _fraud_rate(train_rows),
            "validation": _fraud_rate(validation_rows),
            "test": _fraud_rate(test_rows),
        },
    }
    return DatasetSplits(
        train=_subset(dataset, train_rows, "train", metadata),
        validation=_subset(dataset, validation_rows, "validation", metadata),
        test=_subset(dataset, test_rows, "test", metadata),
        metadata=metadata,
    )


def _ordered_split(
        indexed_rows: list[tuple[int, tuple[dict[str, Any], int]]],
        train_ratio: float,
        validation_ratio: float,
        cutoff_ratio: float | None = None,
        cutoff_timestamp: str | None = None,
) -> tuple[
    list[tuple[int, tuple[dict[str, Any], int]]],
    list[tuple[int, tuple[dict[str, Any], int]]],
    list[tuple[int, tuple[dict[str, Any], int]]],
]:
    if cutoff_timestamp is not None:
        cutoff = datetime.fromisoformat(cutoff_timestamp)
        train_validation = [row for row in indexed_rows if (_timestamp(row[1][0]) or datetime.min) <= cutoff]
        test = [row for row in indexed_rows if (_timestamp(row[1][0]) or datetime.min) > cutoff]
        if len(train_validation) >= 2 and test:
            validation_size = max(1, int(len(train_validation) * validation_ratio))
            return train_validation[:-validation_size], train_validation[-validation_size:], test

    train_size = max(1, int(len(indexed_rows) * (cutoff_ratio or train_ratio)))
    validation_size = max(1, int(len(indexed_rows) * validation_ratio))
    if train_size + validation_size >= len(indexed_rows):
        validation_size = 1
        train_size = len(indexed_rows) - 2
    return (
        indexed_rows[:train_size],
        indexed_rows[train_size:train_size + validation_size],
        indexed_rows[train_size + validation_size:],
    )


def _stratified_temporal_split(
        indexed_rows: list[tuple[int, tuple[dict[str, Any], int]]],
        train_ratio: float,
        validation_ratio: float,
) -> tuple[
    list[tuple[int, tuple[dict[str, Any], int]]],
    list[tuple[int, tuple[dict[str, Any], int]]],
    list[tuple[int, tuple[dict[str, Any], int]]],
]:
    positives = [row for row in indexed_rows if row[1][1] == 1]
    negatives = [row for row in indexed_rows if row[1][1] == 0]
    train = []
    validation = []
    test = []
    for bucket in (positives, negatives):
        bucket_train, bucket_validation, bucket_test = _ordered_split(bucket, train_ratio, validation_ratio)
        train.extend(bucket_train)
        validation.extend(bucket_validation)
        test.extend(bucket_test)
    sorter = lambda item: (_timestamp(item[1][0]) or datetime.min, item[0])
    return sorted(train, key=sorter), sorted(validation, key=sorter), sorted(test, key=sorter)


def _can_stratify(rows: list[tuple[int, tuple[dict[str, Any], int]]], min_fraud_per_split: int) -> bool:
    fraud_cases = sum(1 for _, (_, label) in rows if label == 1)
    return fraud_cases >= min_fraud_per_split * 3


def _class_distribution(rows: list[tuple[int, tuple[dict[str, Any], int]]]) -> dict[str, int]:
    positives = sum(1 for _, (_, label) in rows if label == 1)
    return {"fraud": positives, "legitimate": len(rows) - positives}


def _fraud_rate(rows: list[tuple[int, tuple[dict[str, Any], int]]]) -> float:
    return round(sum(1 for _, (_, label) in rows if label == 1) / len(rows), 6) if rows else 0.0


def _warn_if_imbalanced(
        train: list[tuple[int, tuple[dict[str, Any], int]]],
        validation: list[tuple[int, tuple[dict[str, Any], int]]],
        test: list[tuple[int, tuple[dict[str, Any], int]]],
        tolerance: float,
) -> None:
    rates = [_fraud_rate(rows) for rows in (train, validation, test)]
    if max(rates) - min(rates) > tolerance:
        log.warning("Fraud split is imbalanced: train=%s validation=%s test=%s", rates[0], rates[1], rates[2])


def _subset(dataset: Dataset, rows: list[tuple[int, tuple[dict[str, Any], int]]], name: str, metadata: dict[str, Any]) -> Dataset:
    return Dataset(
        X=[features for _, (features, _) in rows],
        y=[label for _, (_, label) in rows],
        metadata={
            **dataset.metadata,
            "split": name,
            "splitStrategy": metadata["strategy"],
            "sourceRows": [index for index, _ in rows],
        },
    )


def _timestamp(row: dict[str, Any]) -> datetime | None:
    value = row.get("timestamp")
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None
