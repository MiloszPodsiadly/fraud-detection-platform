from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any

from app.data.dataset import Dataset


@dataclass(frozen=True)
class DatasetSplits:
    """Train/validation/test datasets plus split metadata."""

    train: Dataset
    validation: Dataset
    test: Dataset
    metadata: dict[str, Any]


def split_dataset(dataset: Dataset, train_ratio: float = 0.6, validation_ratio: float = 0.2) -> DatasetSplits:
    """Split a dataset, preferring temporal ordering when timestamps are present."""
    if dataset.size < 3:
        raise ValueError("dataset must contain at least 3 rows for train/validation/test split.")
    indexed_rows = list(enumerate(zip(dataset.X, dataset.y)))
    temporal = all(_timestamp(row[0]) is not None for _, row in indexed_rows)
    if temporal:
        indexed_rows.sort(key=lambda item: (_timestamp(item[1][0]), item[0]))

    train_size = max(1, int(dataset.size * train_ratio))
    validation_size = max(1, int(dataset.size * validation_ratio))
    if train_size + validation_size >= dataset.size:
        validation_size = 1
        train_size = dataset.size - 2

    train_rows = indexed_rows[:train_size]
    validation_rows = indexed_rows[train_size:train_size + validation_size]
    test_rows = indexed_rows[train_size + validation_size:]
    strategy = "temporal" if temporal else "deterministic_order"
    metadata = {
        "strategy": strategy,
        "rows": dataset.size,
        "trainRows": len(train_rows),
        "validationRows": len(validation_rows),
        "testRows": len(test_rows),
        "trainIndices": [index for index, _ in train_rows],
        "validationIndices": [index for index, _ in validation_rows],
        "testIndices": [index for index, _ in test_rows],
    }
    return DatasetSplits(
        train=_subset(dataset, train_rows, "train", metadata),
        validation=_subset(dataset, validation_rows, "validation", metadata),
        test=_subset(dataset, test_rows, "test", metadata),
        metadata=metadata,
    )


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
