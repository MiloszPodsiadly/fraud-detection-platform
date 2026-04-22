from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.data.dataset import Dataset


LABELLED_DECISIONS = {
    "CONFIRMED_FRAUD": 1,
    "MARKED_LEGITIMATE": 0,
}


@dataclass(frozen=True)
class FeedbackExample:
    """Privacy-safe analyst feedback row for retraining."""

    feedback_id: str
    transaction_ref: str
    feature_snapshot: dict[str, Any]
    model_score: float | None
    analyst_decision: str
    label: int | None
    decided_at: str
    updated_at: str


def feedback_from_decision_event(event: dict[str, Any]) -> FeedbackExample:
    """Build a privacy-safe feedback example from a FraudDecisionEvent JSON object."""
    metadata = event.get("decisionMetadata") if isinstance(event.get("decisionMetadata"), dict) else {}
    transaction_id = str(event.get("transactionId", "unknown"))
    decision = str(event.get("decision", ""))
    decided_at = str(event.get("decidedAt") or datetime.now(timezone.utc).isoformat())
    return FeedbackExample(
        feedback_id=_stable_hash(f"{transaction_id}:{event.get('decisionId', '')}"),
        transaction_ref=_stable_hash(transaction_id),
        feature_snapshot=_safe_snapshot(metadata.get("featureSnapshot")),
        model_score=_number_or_none(metadata.get("modelScore")),
        analyst_decision=decision,
        label=LABELLED_DECISIONS.get(decision),
        decided_at=decided_at,
        updated_at=decided_at,
    )


def dataset_from_feedback(examples: list[FeedbackExample]) -> Dataset:
    """Create a training Dataset from labelled analyst feedback."""
    labelled = [example for example in examples if example.label is not None]
    return Dataset(
        X=[example.feature_snapshot for example in labelled],
        y=[int(example.label) for example in labelled if example.label is not None],
        metadata={
            "source": "analyst-feedback",
            "examples": len(labelled),
            "privacy": "hashed identifiers only",
        },
    )


class FeedbackDatasetStore:
    """Local JSONL store for versioned analyst feedback datasets."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def save_version(self, examples: list[FeedbackExample], version: str | None = None) -> Path:
        """Write a versioned feedback dataset file."""
        version = version or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        path = self.root / f"feedback-{version}.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        lines = [json.dumps(asdict(example), sort_keys=True) for example in examples]
        path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
        return path

    def load_version(self, path: Path) -> list[FeedbackExample]:
        """Load feedback examples from a JSONL version file."""
        examples: list[FeedbackExample] = []
        if not path.exists():
            return examples
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            examples.append(FeedbackExample(**json.loads(line)))
        return examples

    def latest(self) -> Path | None:
        """Return the most recent feedback dataset version path."""
        versions = sorted(self.root.glob("feedback-*.jsonl"))
        return versions[-1] if versions else None

    def update_label(self, path: Path, feedback_id: str, analyst_decision: str) -> Path:
        """Apply a delayed analyst label update and write a new dataset version."""
        examples = self.load_version(path)
        now = datetime.now(timezone.utc).isoformat()
        updated = [
            replace(
                example,
                analyst_decision=analyst_decision,
                label=LABELLED_DECISIONS.get(analyst_decision),
                updated_at=now,
            )
            if example.feedback_id == feedback_id else example
            for example in examples
        ]
        return self.save_version(updated)


def _stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:24]


def _safe_snapshot(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    return {
        str(key): item
        for key, item in value.items()
        if isinstance(item, (str, int, float, bool, list, dict)) or item is None
    }


def _number_or_none(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
