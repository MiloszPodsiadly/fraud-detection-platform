from __future__ import annotations

import json
import shutil
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class ModelRegistryEntry:
    """Metadata for one locally registered model artifact."""

    model_version: str
    model_type: str
    artifact_path: str
    metrics: dict[str, Any]
    training_metadata: dict[str, Any]
    created_at: str
    role: str


class ModelRegistry:
    """Simple local file-backed model registry."""

    INDEX_NAME = "registry.json"

    def __init__(self, root: Path) -> None:
        self.root = root
        self.artifacts_root = root / "artifacts"
        self.index_path = root / self.INDEX_NAME

    def register(
            self,
            artifact_path: Path,
            model_version: str,
            model_type: str,
            metrics: dict[str, Any] | None = None,
            training_metadata: dict[str, Any] | None = None,
            role: str = "challenger",
    ) -> ModelRegistryEntry:
        """Copy an artifact into the registry and record metadata."""
        self.artifacts_root.mkdir(parents=True, exist_ok=True)
        target = self.artifacts_root / f"{model_version}.json"
        if artifact_path.resolve() != target.resolve():
            shutil.copyfile(artifact_path, target)

        entry = ModelRegistryEntry(
            model_version=model_version,
            model_type=model_type,
            artifact_path=str(target),
            metrics=metrics or {},
            training_metadata=training_metadata or {},
            created_at=datetime.now(timezone.utc).isoformat(),
            role=role,
        )
        entries = [existing for existing in self.entries() if existing.model_version != model_version]
        if role == "champion":
            entries = [self._with_role(existing, "archived") if existing.role == "champion" else existing for existing in entries]
        entries.append(entry)
        self._write_entries(entries)
        return entry

    def latest(self) -> ModelRegistryEntry | None:
        """Load the most recently registered model."""
        entries = self.entries()
        return max(entries, key=lambda item: item.created_at) if entries else None

    def by_version(self, model_version: str) -> ModelRegistryEntry | None:
        """Load a registry entry by model version."""
        for entry in self.entries():
            if entry.model_version == model_version:
                return entry
        return None

    def champion(self) -> ModelRegistryEntry | None:
        """Load the champion model entry."""
        return self._by_role("champion")

    def challenger(self) -> ModelRegistryEntry | None:
        """Load the challenger model entry."""
        return self._by_role("challenger")

    def promote(self, model_version: str) -> ModelRegistryEntry:
        """Promote an existing model version to champion."""
        entries = self.entries()
        promoted: ModelRegistryEntry | None = None
        updated: list[ModelRegistryEntry] = []
        for entry in entries:
            if entry.model_version == model_version:
                promoted = self._with_role(entry, "champion")
                updated.append(promoted)
            elif entry.role == "champion":
                updated.append(self._with_role(entry, "archived"))
            else:
                updated.append(entry)
        if promoted is None:
            raise ValueError(f"Unknown model version: {model_version}")
        self._write_entries(updated)
        return promoted

    def entries(self) -> list[ModelRegistryEntry]:
        """Load all registry entries."""
        if not self.index_path.exists():
            return []
        payload = json.loads(self.index_path.read_text(encoding="utf-8"))
        rows = payload.get("models", []) if isinstance(payload, dict) else []
        return [ModelRegistryEntry(**row) for row in rows]

    def _by_role(self, role: str) -> ModelRegistryEntry | None:
        candidates = [entry for entry in self.entries() if entry.role == role]
        return max(candidates, key=lambda item: item.created_at) if candidates else None

    def _write_entries(self, entries: list[ModelRegistryEntry]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        payload = {"models": [asdict(entry) for entry in sorted(entries, key=lambda item: item.created_at)]}
        self.index_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    def _with_role(self, entry: ModelRegistryEntry, role: str) -> ModelRegistryEntry:
        return ModelRegistryEntry(
            model_version=entry.model_version,
            model_type=entry.model_type,
            artifact_path=entry.artifact_path,
            metrics=entry.metrics,
            training_metadata=entry.training_metadata,
            created_at=entry.created_at,
            role=role,
        )


def default_registry_path() -> Path:
    """Default registry directory under the ML service app folder."""
    return Path(__file__).resolve().parents[1] / "model_registry"
