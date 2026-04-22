from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class Dataset:
    """Training dataset with features, labels, and generation metadata."""

    X: list[dict[str, Any]]
    y: list[int]
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if len(self.X) != len(self.y):
            raise ValueError("Dataset X and y must contain the same number of rows.")

    @property
    def size(self) -> int:
        """Number of examples in the dataset."""
        return len(self.X)
