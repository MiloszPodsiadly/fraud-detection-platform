from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from app.model_identity_policy import ModelLogicalIdentity
from app.models.model_loader import (
    ModelConfigurationError,
    ValidatedModelArtifact,
    load_validated_model_artifact,
)
from app.registry.model_registry import ModelRegistry


class ModelSelectionMode(str, Enum):
    PACKAGED_EXPLICIT = "PACKAGED_EXPLICIT"
    REGISTRY_EXACT = "REGISTRY_EXACT"


class ModelSelectionError(ModelConfigurationError):
    """Raised when the configured model cannot be selected exactly."""


@dataclass(frozen=True)
class ModelSelectionPolicy:
    """One explicit, fail-closed source for the production inference model."""

    mode: ModelSelectionMode | str
    model_name: str | None = None
    model_version: str | None = None

    def __post_init__(self) -> None:
        try:
            mode = ModelSelectionMode(self.mode)
        except ValueError as exception:
            raise ModelSelectionError(f"Unsupported model selection mode: {self.mode!r}.") from exception
        object.__setattr__(self, "mode", mode)

        if mode is ModelSelectionMode.PACKAGED_EXPLICIT:
            if self.model_name is not None or self.model_version is not None:
                raise ModelSelectionError(
                    "PACKAGED_EXPLICIT selection must not include a registry model identity."
                )
            return

        try:
            identity = ModelLogicalIdentity(self.model_name, self.model_version)
        except ValueError as exception:
            raise ModelSelectionError(
                "REGISTRY_EXACT selection requires a valid modelName and modelVersion."
            ) from exception
        object.__setattr__(self, "model_name", identity.model_name)
        object.__setattr__(self, "model_version", identity.model_version)

    @classmethod
    def packaged_explicit(cls) -> ModelSelectionPolicy:
        return cls(ModelSelectionMode.PACKAGED_EXPLICIT)

    @classmethod
    def registry_exact(cls, model_name: str, model_version: str) -> ModelSelectionPolicy:
        return cls(ModelSelectionMode.REGISTRY_EXACT, model_name, model_version)

    @property
    def logical_identity(self) -> ModelLogicalIdentity | None:
        if self.mode is ModelSelectionMode.PACKAGED_EXPLICIT:
            return None
        return ModelLogicalIdentity(self.model_name, self.model_version)


def select_model_artifact(
        policy: ModelSelectionPolicy,
        packaged_artifact_path: Path | None,
        registry: ModelRegistry | None,
) -> ValidatedModelArtifact:
    """Resolve exactly the configured model source without role or recency fallback."""
    if policy.mode is ModelSelectionMode.PACKAGED_EXPLICIT:
        if packaged_artifact_path is None:
            raise ModelSelectionError("PACKAGED_EXPLICIT selection requires an artifact path.")
        return load_validated_model_artifact(packaged_artifact_path)

    if registry is None:
        raise ModelSelectionError("REGISTRY_EXACT selection requires a model registry.")
    identity = policy.logical_identity
    if identity is None:
        raise ModelSelectionError("REGISTRY_EXACT selection requires a logical model identity.")
    entry = registry.by_identity(identity.model_name, identity.model_version)
    if entry is None:
        raise ModelSelectionError(
            "Requested registry model identity is unavailable: "
            f"{identity.model_name}/{identity.model_version}."
        )
    return registry.resolve(entry)
