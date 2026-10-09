from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Mapping

from app.inference.model_selection import ModelSelectionError, ModelSelectionMode, ModelSelectionPolicy
from app.inference.model_runtime import (
    FraudModelRuntime,
    ResolvedModelRuntime,
    resolve_model_runtime,
)
from app.registry.model_registry import ModelRegistry, default_registry_path


DEFAULT_ARTIFACT_PATH = Path(__file__).with_name("model_artifact.json")
MODEL_SELECTION_MODE_ENV = "ML_MODEL_SELECTION_MODE"
MODEL_NAME_ENV = "ML_MODEL_NAME"
MODEL_VERSION_ENV = "ML_MODEL_VERSION"
MODEL_REGISTRY_PATH_ENV = "ML_MODEL_REGISTRY_PATH"


class FraudModel:
    """Public fraud model facade kept compatible with the scoring service."""

    def __init__(
            self,
            resolved_runtime: ResolvedModelRuntime,
    ) -> None:
        self.resolved_runtime = resolved_runtime
        self._runtime = FraudModelRuntime(resolved_runtime)

    @classmethod
    def from_selection(
            cls,
            artifact_path: Path | None,
            selection_policy: ModelSelectionPolicy,
            registry: ModelRegistry | None = None,
    ) -> FraudModel:
        return cls(resolve_model_runtime(artifact_path, selection_policy, registry))

    @classmethod
    def from_packaged_artifact(cls, artifact_path: Path = DEFAULT_ARTIFACT_PATH) -> FraudModel:
        return cls.from_selection(artifact_path, ModelSelectionPolicy.packaged_explicit())

    @classmethod
    def from_registry_exact(
            cls,
            model_name: str,
            model_version: str,
            registry: ModelRegistry,
    ) -> FraudModel:
        return cls.from_selection(
            None,
            ModelSelectionPolicy.registry_exact(model_name, model_version),
            registry,
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

    @property
    def feature_contract_version(self) -> str:
        """Feature contract version declared by the loaded model artifact."""
        return self._runtime.feature_contract_version

    @property
    def model_artifact_sha256(self) -> str:
        return self._runtime.model_artifact_sha256

    def score(self, features: dict[str, Any]) -> dict[str, Any]:
        """Score feature payloads using the production runtime."""
        return self._runtime.score(features)

    def compare_with(
            self,
            features: dict[str, Any],
            other: FraudModel,
    ) -> dict[str, Any]:
        """Compare this model with another already resolved ML runtime."""
        return self._runtime.compare_with(other._runtime, features)


def model_selection_policy_from_environment(
        environment: Mapping[str, str] = os.environ,
) -> ModelSelectionPolicy:
    mode = environment.get(MODEL_SELECTION_MODE_ENV, ModelSelectionMode.PACKAGED_EXPLICIT.value)
    return ModelSelectionPolicy(
        mode=mode,
        model_name=environment.get(MODEL_NAME_ENV),
        model_version=environment.get(MODEL_VERSION_ENV),
    )


def resolve_configured_model_runtime(
        environment: Mapping[str, str] = os.environ,
) -> ResolvedModelRuntime:
    policy = model_selection_policy_from_environment(environment)
    registry = None
    if policy.mode is ModelSelectionMode.REGISTRY_EXACT:
        configured_registry_path = environment.get(MODEL_REGISTRY_PATH_ENV)
        if configured_registry_path is not None and not configured_registry_path.strip():
            raise ModelSelectionError(f"{MODEL_REGISTRY_PATH_ENV} must not be blank.")
        registry_path = Path(configured_registry_path) if configured_registry_path else default_registry_path()
        registry = ModelRegistry(registry_path)
    elif MODEL_REGISTRY_PATH_ENV in environment:
        raise ModelSelectionError(
            f"{MODEL_REGISTRY_PATH_ENV} is only valid with REGISTRY_EXACT selection."
        )
    artifact_path = DEFAULT_ARTIFACT_PATH if policy.mode is ModelSelectionMode.PACKAGED_EXPLICIT else None
    return resolve_model_runtime(artifact_path, policy, registry)
