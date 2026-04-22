from __future__ import annotations

from pathlib import Path
from typing import Any

from app.inference.model_runtime import FraudModelRuntime
from app.registry.model_registry import ModelRegistry


DEFAULT_ARTIFACT_PATH = Path(__file__).with_name("model_artifact.json")


class FraudModel:
    """Public fraud model facade kept compatible with the scoring service."""

    def __init__(
            self,
            artifact_path: Path = DEFAULT_ARTIFACT_PATH,
            model_version: str | None = None,
            registry_role: str = "champion",
            registry: ModelRegistry | None = None,
    ) -> None:
        self._runtime = FraudModelRuntime(
            artifact_path,
            model_version=model_version,
            registry_role=registry_role,
            registry=registry,
        )

    @property
    def model_name(self) -> str:
        """Name of the loaded model."""
        return self._runtime.model_name

    @property
    def model_version(self) -> str:
        """Version of the loaded model."""
        return self._runtime.model_version

    @property
    def model_family(self) -> str:
        """Family of the loaded model."""
        return self._runtime.model_family

    def score(self, features: dict[str, Any]) -> dict[str, Any]:
        """Score feature payloads using the production runtime."""
        return self._runtime.score(features)


_DEFAULT_MODEL = FraudModel()
MODEL_NAME = _DEFAULT_MODEL.model_name
MODEL_VERSION = _DEFAULT_MODEL.model_version
